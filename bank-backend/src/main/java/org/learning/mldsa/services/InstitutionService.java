package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.CustomerResponse;
import org.learning.mldsa.dtos.ProvisionRequest;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.AccountType;
import org.learning.mldsa.models.DebitCard;
import org.learning.mldsa.repositories.AccountBalance;
import org.learning.mldsa.repositories.AccountRepository;
import org.learning.mldsa.repositories.DebitCardRepository;
import org.learning.mldsa.repositories.PostingRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An institution's view of, and operations on, its own customers.
 *
 * Every method takes the institution's id — which the controller reads from the caller's
 * token — and filters by it inside the query itself. That is the tenancy boundary:
 * Institution X must never read or change Institution Y's customers, and it is enforced here
 * rather than by what the UI happens to offer. A customer held at another institution is
 * reported as not found, the same answer as an id that was never issued, so there is nothing
 * to probe.
 */
@RequiredArgsConstructor
@Service
public class InstitutionService {

    private final UserService userService;
    private final AccountService accountService;
    private final CardService cardService;
    private final AccountRepository accountRepository;
    private final PostingRepository postingRepository;
    private final DebitCardRepository debitCardRepository;

    @Transactional
    public CustomerResponse createCustomer(Long institutionId, ProvisionRequest request) {
        Account account = userService.createCustomer(institutionId, request);
        // A new account has no postings and no card yet.
        return toResponse(account, BigDecimal.ZERO, null);
    }

    /**
     * A page of the institution's customers, newest first.
     *
     * Balances and cards are fetched once for the page and matched up in memory, so a page
     * costs the same few queries however many rows it holds.
     */
    public Page<CustomerResponse> listCustomers(Long institutionId, Pageable pageable) {
        Page<Account> accounts = accountRepository.findByInstitution_UserIdAndTypeOrderByOpenedAtDescAccountIdDesc(
                institutionId, AccountType.CUSTOMER, pageable);

        List<Long> accountIds = accounts.map(Account::getAccountId).getContent();
        if (accountIds.isEmpty()) {
            // Keeps the total, so a client asking past the last page can step back.
            return new PageImpl<>(List.of(), pageable, accounts.getTotalElements());
        }

        Map<Long, BigDecimal> balances = postingRepository.sumBalancesOf(accountIds).stream()
                .collect(Collectors.toMap(AccountBalance::accountId, AccountBalance::balance));
        Map<Long, DebitCard> cards = debitCardRepository.findByAccount_AccountIdIn(accountIds).stream()
                .collect(Collectors.toMap(card -> card.getAccount().getAccountId(), Function.identity()));

        return accounts.map(account -> toResponse(
                account,
                balances.getOrDefault(account.getAccountId(), BigDecimal.ZERO),
                cards.get(account.getAccountId())));
    }

    public CustomerResponse requireCustomer(Long institutionId, Long customerId) {
        Account account = requireCustomerAccount(institutionId, customerId);
        return toResponse(
                account,
                accountService.balanceOf(account.getAccountId()),
                debitCardRepository.findByAccount_AccountId(account.getAccountId()).orElse(null));
    }

    /**
     * Returns a customer's blocked card to service — after three wrong PINs, for example.
     *
     * The customer can block their own card but not reverse it; their institution is the bank
     * that can. The account is resolved within the calling institution first, so another
     * bank's customer is not found rather than unblocked.
     */
    @Transactional
    public CustomerResponse unblockCard(Long institutionId, Long customerId) {
        Account account = requireCustomerAccount(institutionId, customerId);
        DebitCard card = cardService.unblock(account);
        return toResponse(account, accountService.balanceOf(account.getAccountId()), card);
    }

    private Account requireCustomerAccount(Long institutionId, Long customerId) {
        return accountRepository.findByOwner_UserIdAndInstitution_UserIdAndType(
                        customerId, institutionId, AccountType.CUSTOMER)
                .orElseThrow(() -> new RuntimeException("Customer not found"));
    }

    private static CustomerResponse toResponse(Account account, BigDecimal balance, DebitCard card) {
        return new CustomerResponse(
                account.getOwner().getUserId(),
                account.getOwner().getName(),
                account.getAccountNumber(),
                account.getCurrency(),
                balance,
                account.getOpenedAt(),
                card == null ? null : card.effectiveStatus(),
                card == null ? null : card.maskedNumber()
        );
    }
}
