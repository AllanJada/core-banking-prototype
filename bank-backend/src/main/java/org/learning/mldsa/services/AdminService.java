package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.AdminAccountResponse;
import org.learning.mldsa.dtos.AdminSummaryResponse;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.DebitCard;
import org.learning.mldsa.models.FileTransfer;
import org.learning.mldsa.models.Payment;
import org.learning.mldsa.models.PaymentStatus;
import org.learning.mldsa.models.Role;
import org.learning.mldsa.repositories.AccountBalance;
import org.learning.mldsa.repositories.AccountRepository;
import org.learning.mldsa.repositories.DebitCardRepository;
import org.learning.mldsa.repositories.FileTransferRepository;
import org.learning.mldsa.repositories.PaymentRepository;
import org.learning.mldsa.repositories.PostingRepository;
import org.learning.mldsa.repositories.UserRepositories;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The read model behind the Bank role's oversight console.
 *
 * Read-only by design. This role watches the platform rather than operating on customers'
 * money: there is no method here that moves a balance, alters a payment, or touches a
 * card. The one thing the Bank role can create is an account, and that lives with the rest
 * of user provisioning rather than here.
 *
 * Everything it returns is aggregated or masked. An oversight view needs to show that an
 * account exists and what it holds, not the key material or PIN belonging to it.
 */
@RequiredArgsConstructor
@Service
public class AdminService {

    private final UserRepositories userRepositories;
    private final AccountRepository accountRepository;
    private final PostingRepository postingRepository;
    private final PaymentRepository paymentRepository;
    private final FileTransferRepository fileTransferRepository;
    private final DebitCardRepository debitCardRepository;

    @Value("${app.ledger.default-currency}")
    private String currency;

    /**
     * Every account, with its balance and card status.
     *
     * Balances and cards are each fetched once for the whole list and then matched up in
     * memory, rather than queried per account — three queries regardless of how many
     * accounts there are.
     */
    public List<AdminAccountResponse> listAccounts() {
        Map<Long, BigDecimal> balances = postingRepository.sumBalancesByAccount().stream()
                .collect(Collectors.toMap(AccountBalance::accountId, AccountBalance::balance));

        Map<Long, DebitCard> cards = debitCardRepository.findAll().stream()
                .collect(Collectors.toMap(card -> card.getAccount().getAccountId(), Function.identity()));

        return accountRepository.findAll().stream()
                .sorted(Comparator.comparing(Account::getAccountId))
                .map(account -> {
                    DebitCard card = cards.get(account.getAccountId());
                    return new AdminAccountResponse(
                            account.getAccountId(),
                            account.getAccountNumber(),
                            account.getOwner().getName(),
                            account.getCurrency(),
                            // Absent from the grouped result means no postings yet, which is
                            // a balance of zero rather than an unknown one.
                            balances.getOrDefault(account.getAccountId(), BigDecimal.ZERO),
                            account.getOpenedAt(),
                            card == null ? null : card.effectiveStatus(),
                            card == null ? null : card.maskedNumber()
                    );
                })
                .toList();
    }

    public Page<Payment> listPayments(Pageable pageable) {
        return paymentRepository.findAllByOrderByCreatedAtDescPaymentIdDesc(pageable);
    }

    public Page<FileTransfer> listTransfers(Pageable pageable) {
        return fileTransferRepository.findAllByOrderBySentAtDescTransferIdDesc(pageable);
    }

    public AdminSummaryResponse summary() {
        Map<Role, Long> byRole = userRepositories.findAll().stream()
                .collect(Collectors.groupingBy(org.learning.mldsa.models.User::getRole, Collectors.counting()));

        BigDecimal totalHeld = postingRepository.sumBalancesByAccount().stream()
                .map(AccountBalance::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Payment> payments = paymentRepository.findAll();
        long completed = payments.stream().filter(p -> p.getStatus() == PaymentStatus.COMPLETED).count();
        BigDecimal completedVolume = payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.COMPLETED)
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<FileTransfer> transfers = fileTransferRepository.findAll();

        return new AdminSummaryResponse(
                byRole.getOrDefault(Role.NORMAL_USER, 0L),
                byRole.getOrDefault(Role.INSTITUTION, 0L),
                byRole.getOrDefault(Role.BANK, 0L),
                accountRepository.count(),
                totalHeld,
                currency,
                completed,
                payments.size() - completed,
                completedVolume,
                transfers.size(),
                transfers.stream().filter(t -> t.getStoredXmlFilename() != null).count()
        );
    }
}
