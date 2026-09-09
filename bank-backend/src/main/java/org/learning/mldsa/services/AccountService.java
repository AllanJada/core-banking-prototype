package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.Posting;
import org.learning.mldsa.models.PostingDirection;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.AccountRepository;
import org.learning.mldsa.repositories.PostingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The ledger: accounts, the postings recorded against them, and the balances derived
 * from those postings.
 *
 * This service owns the only path by which a posting may be written. Nothing else is
 * allowed to record one, so every movement of money in the system passes through
 * recordPosting and lands in the same append-only history.
 */
@RequiredArgsConstructor
@Service
public class AccountService {

    private static final int ACCOUNT_NUMBER_LENGTH = 16;
    // A collision means re-rolling, not failing. Retries are bounded so a misconfigured
    // generator surfaces as a clear error rather than an infinite loop.
    private static final int MAX_ACCOUNT_NUMBER_ATTEMPTS = 10;

    private final AccountRepository accountRepository;
    private final PostingRepository postingRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.ledger.default-currency}")
    private String defaultCurrency;

    /**
     * Opens an account for a newly registered customer.
     *
     * Idempotent by ownership: an owner who already has an account gets that one back
     * rather than a second, so retried registration can't quietly leave a customer with
     * two accounts and a split balance.
     */
    @Transactional
    public Account openAccountFor(User owner) {
        return accountRepository.findByOwner_UserId(owner.getUserId())
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setAccountNumber(generateUniqueAccountNumber());
                    account.setOwner(owner);
                    account.setCurrency(defaultCurrency);
                    account.setOpenedAt(Instant.now());
                    return accountRepository.save(account);
                });
    }

    public Account requireAccountFor(Long ownerId) {
        return accountRepository.findByOwner_UserId(ownerId)
                .orElseThrow(() -> new RuntimeException("No account is open for this user"));
    }

    /** Current balance, summed from the account's full posting history. */
    public BigDecimal balanceOf(Long accountId) {
        return postingRepository.sumBalance(accountId);
    }

    /**
     * A page of the account's history, newest first.
     *
     * Paged because an account's postings only ever accumulate: the history that is cheap to
     * return on day one is the same query that would return years of rows later. Statements
     * deliberately do not go through here — they need every posting in their period, which
     * is why findForStatement stays unpaged.
     */
    public Page<Posting> listPostings(Long accountId, Pageable pageable) {
        return postingRepository.findByAccount_AccountIdOrderByPostedAtDescPostingIdDesc(accountId, pageable);
    }

    /**
     * Appends a single posting to an account's history.
     *
     * Callers that move money between two accounts must write both sides through this
     * method inside one transaction, passing the same transactionRef, so a debit can never
     * be committed without its matching credit. newTransactionRef() supplies that value.
     *
     * The amount must be positive — direction is what decides whether it raises or lowers
     * the balance. Accepting a negative amount here would allow a "credit" that silently
     * debits.
     */
    @Transactional
    public Posting recordPosting(Account account, PostingDirection direction, BigDecimal amount,
                                 String description, String transactionRef) {
        if (amount == null || amount.signum() <= 0) {
            throw new RuntimeException("Posting amount must be greater than zero");
        }

        Posting posting = new Posting();
        posting.setAccount(account);
        posting.setDirection(direction);
        posting.setAmount(amount);
        posting.setDescription(description);
        posting.setTransactionRef(transactionRef);
        posting.setPostedAt(Instant.now());

        return postingRepository.save(posting);
    }

    /**
     * Moves money between two accounts as one indivisible operation.
     *
     * Both postings are written under a single transaction and share one transactionRef,
     * so a debit can never be committed without its matching credit. This is why the
     * pairing lives here rather than in the code that decides to make a payment: callers
     * cannot write one side, do something else, and then write the other.
     *
     * Balance and limit checks are the caller's responsibility. By the time a transfer
     * reaches this method, the decision to move the money has already been made.
     */
    @Transactional
    public String transfer(Account from, Account to, BigDecimal amount, String description) {
        String transactionRef = newTransactionRef();
        recordPosting(from, PostingDirection.DEBIT, amount, description, transactionRef);
        recordPosting(to, PostingDirection.CREDIT, amount, description, transactionRef);
        return transactionRef;
    }

    /** Credits an account with no counterparty — what a deposit does. */
    @Transactional
    public String credit(Account account, BigDecimal amount, String description) {
        String transactionRef = newTransactionRef();
        recordPosting(account, PostingDirection.CREDIT, amount, description, transactionRef);
        return transactionRef;
    }

    /** A fresh identifier tying together the postings that make up one operation. */
    public String newTransactionRef() {
        return UUID.randomUUID().toString();
    }

    private String generateUniqueAccountNumber() {
        for (int attempt = 0; attempt < MAX_ACCOUNT_NUMBER_ATTEMPTS; attempt++) {
            String candidate = randomAccountNumber();
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new RuntimeException("Could not allocate a unique account number");
    }

    private String randomAccountNumber() {
        StringBuilder digits = new StringBuilder(ACCOUNT_NUMBER_LENGTH);
        // First digit is 1-9 so the number is always a full 16 characters and never
        // loses a leading zero if anything downstream treats it as numeric.
        digits.append(1 + secureRandom.nextInt(9));
        for (int i = 1; i < ACCOUNT_NUMBER_LENGTH; i++) {
            digits.append(secureRandom.nextInt(10));
        }
        return digits.toString();
    }
}
