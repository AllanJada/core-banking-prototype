package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.AccountType;
import org.learning.mldsa.models.Deposit;
import org.learning.mldsa.models.Payment;
import org.learning.mldsa.models.PaymentStatus;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.AccountRepository;
import org.learning.mldsa.repositories.DepositRepository;
import org.learning.mldsa.repositories.PaymentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Deposits and payments: the operations that put money into the ledger and move it
 * between accounts.
 *
 * Each one follows the pattern the slip flow established — build the record, sign it, and
 * persist it in a single call, with no step in between where a client could alter what was
 * agreed. The reason that matters more here than for a document is immediate: a slip
 * altered between composing and signing is a bad document, whereas a payment amount
 * altered in that window moves the wrong money and cannot be taken back.
 */
@RequiredArgsConstructor
@Service
public class PaymentService {

    /**
     * What a customer is told when their bank could not settle the payment.
     *
     * Deliberately says nothing about which bank or why: a customer-facing message naming
     * another institution's liquidity would leak it, and the cause is not theirs to fix.
     */
    private static final String SETTLEMENT_REFUSED = "The payment could not be settled";

    private final AccountService accountService;
    private final AccountRepository accountRepository;
    private final PaymentRepository paymentRepository;
    private final DepositRepository depositRepository;
    private final CryptoService cryptoService;
    private final FailedPaymentRecorder failedPaymentRecorder;

    @Value("${app.payments.max-per-transaction}")
    private BigDecimal maxPerTransaction;

    @Value("${app.payments.max-per-day}")
    private BigDecimal maxPerDay;

    @Value("${app.settlement.net-debit-cap}")
    private BigDecimal netDebitCap;

    /**
     * Credits the caller's own account.
     *
     * The per-transaction and daily caps deliberately do not apply: they exist to bound
     * what can leave an account, and a deposit only ever adds.
     */
    @Transactional
    public Deposit deposit(Long userId, BigDecimal amount, String description) {
        requireWellFormedAmount(amount);

        Account account = accountService.requireAccountFor(userId);
        Instant depositedAt = Instant.now();

        String envelope = cryptoService.buildDepositEnvelope(
                account.getAccountNumber(), amount, depositedAt.toEpochMilli());
        String signature = cryptoService.sign(envelope, ownerPrivateKey(account));

        String transactionRef = accountService.credit(account, amount, description);

        Deposit deposit = new Deposit();
        deposit.setAccount(account);
        deposit.setAmount(amount);
        deposit.setDescription(description);
        deposit.setTransactionRef(transactionRef);
        deposit.setSignature(signature);
        deposit.setDepositedAt(depositedAt);

        return depositRepository.save(deposit);
    }

    /**
     * Pays another account.
     *
     * Every rejection below is recorded as a FAILED payment before the exception is
     * thrown, so the attempt is not lost when this transaction rolls back. The checks run
     * before anything is written: by the time the transfer happens, the payment is known
     * to be one this account is allowed to make.
     */
    @Transactional
    public Payment pay(Long userId, String toAccountNumber, BigDecimal amount, String description) {
        // A malformed amount is a broken request rather than a refused payment, so it is
        // rejected outright without leaving a record of an attempt that never made sense.
        requireWellFormedAmount(amount);

        Account from = accountService.requireAccountFor(userId);

        Account to = accountRepository.findByAccountNumber(toAccountNumber)
                // Settlement accounts move only through inter-bank settlement, never by a
                // customer paying one. Refused exactly as an unknown number is, so this
                // endpoint can't be used to find out which numbers are settlement accounts.
                .filter(account -> account.getType() == AccountType.CUSTOMER)
                .orElse(null);
        if (to == null) {
            throw reject(from, null, amount, description, "No account exists with that number");
        }
        if (to.getAccountId().equals(from.getAccountId())) {
            throw reject(from, to, amount, description, "An account cannot pay itself");
        }
        if (amount.compareTo(maxPerTransaction) > 0) {
            throw reject(from, to, amount, description,
                    "Amount exceeds the per-transaction limit of " + maxPerTransaction);
        }

        // Overdrafts are not offered: a payment that would take the account below zero is
        // refused rather than allowed to run a negative balance.
        BigDecimal balance = accountService.balanceOf(from.getAccountId());
        if (balance.compareTo(amount) < 0) {
            throw reject(from, to, amount, description, "Insufficient funds");
        }

        BigDecimal paidToday = paymentRepository.sumCompletedSince(from.getAccountId(), startOfToday());
        if (paidToday.add(amount).compareTo(maxPerDay) > 0) {
            throw reject(from, to, amount, description,
                    "Amount would exceed the daily limit of " + maxPerDay);
        }

        // Whether this payment has to settle between banks is decided by comparing the two
        // accounts' institutions — never by anything the payer sends.
        User payerBank = from.getInstitution();
        User payeeBank = to.getInstitution();
        boolean interBank = !payerBank.getUserId().equals(payeeBank.getUserId());

        Account payerSettlement = null;
        Account payeeSettlement = null;
        if (interBank) {
            // Locked before its position is read, so two payments leaving this institution at
            // once cannot both be told there is room for one of them.
            payerSettlement = requireSettlementForUpdate(payerBank);
            payeeSettlement = requireSettlement(payeeBank);

            BigDecimal position = accountService.balanceOf(payerSettlement.getAccountId());
            if (position.subtract(amount).compareTo(netDebitCap.negate()) < 0) {
                // The customer is told only that it could not be settled: naming their bank's
                // liquidity to them would leak it, and it is not their doing. The specific
                // cause is recorded for that institution and the Central Bank.
                throw reject(from, to, amount, description, SETTLEMENT_REFUSED,
                        payerBank.getName() + " would exceed its net debit cap of " + netDebitCap
                                + " (position " + position + ", payment " + amount + ")");
            }
        }

        Instant createdAt = Instant.now();
        String envelope = cryptoService.buildPaymentEnvelope(
                from.getAccountNumber(), to.getAccountNumber(), amount, createdAt.toEpochMilli());
        String signature = cryptoService.sign(envelope, ownerPrivateKey(from));

        // The postings are written together by the ledger, so a debit cannot land without its
        // credit: two of them within one bank, four when the money crosses banks.
        String transactionRef = interBank
                ? accountService.settleInterBank(from, payerSettlement, payeeSettlement, to, amount, description)
                : accountService.transfer(from, to, amount, description);

        Payment payment = new Payment();
        payment.setFromAccount(from);
        payment.setToAccount(to);
        payment.setAmount(amount);
        payment.setDescription(description);
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setTransactionRef(transactionRef);
        payment.setSignature(signature);
        payment.setCreatedAt(createdAt);

        return paymentRepository.save(payment);
    }

    /** A page of payments this account has sent, successful and refused alike, newest first. */
    public Page<Payment> listPaymentsFor(Long userId, Pageable pageable) {
        Account account = accountService.requireAccountFor(userId);
        return paymentRepository.findByFromAccount_AccountIdOrderByCreatedAtDescPaymentIdDesc(
                account.getAccountId(), pageable);
    }

    /**
     * Records the refusal and builds the exception that aborts the payment.
     *
     * Returns the exception for the caller to throw rather than throwing it directly, so
     * every rejection reads as `throw reject(...)`. That keeps the control flow visible to
     * the compiler too: after a rejection the code below is unreachable, which is what
     * makes it safe to use a recipient that was null-checked a few lines earlier.
     */
    private RuntimeException reject(Account from, Account to, BigDecimal amount,
                                    String description, String reason) {
        return reject(from, to, amount, description, reason, null);
    }

    /**
     * As above, for a refusal whose specific cause is not the customer's business — the
     * reason is what they are told, the detail is kept for their institution and the Central
     * Bank.
     */
    private RuntimeException reject(Account from, Account to, BigDecimal amount,
                                    String description, String reason, String detail) {
        failedPaymentRecorder.record(
                from.getAccountId(),
                to == null ? null : to.getAccountId(),
                amount, description, reason, detail);
        return new RuntimeException(reason);
    }

    private Account requireSettlementForUpdate(User institution) {
        return accountRepository.findSettlementForUpdate(institution.getUserId())
                .orElseThrow(() -> new RuntimeException(
                        "No settlement account is open for " + institution.getName()));
    }

    private Account requireSettlement(User institution) {
        return accountRepository.findByOwner_UserIdAndType(institution.getUserId(), AccountType.SETTLEMENT)
                .orElseThrow(() -> new RuntimeException(
                        "No settlement account is open for " + institution.getName()));
    }

    /**
     * Rejects an amount that is not money this system can move: zero or negative, or
     * carrying more precision than a currency has.
     *
     * Public because payment requests are checked against the same rule when they are
     * created — catching a bad amount at that point is better than letting it sit in a
     * link until someone tries to pay it.
     */
    public void requireWellFormedAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new RuntimeException("Amount must be greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new RuntimeException("Amount must have at most 2 decimal places");
        }
    }

    private PrivateKey ownerPrivateKey(Account account) {
        return cryptoService.signingKeyOf(account.getOwner());
    }

    /**
     * Midnight UTC, as the boundary for the daily cap.
     *
     * UTC rather than a local zone so the window is unambiguous; a customer in another
     * timezone will see it reset at a time that is not their midnight, which is worth
     * revisiting once accounts carry a locale.
     */
    private Instant startOfToday() {
        return Instant.now().truncatedTo(ChronoUnit.DAYS);
    }
}
