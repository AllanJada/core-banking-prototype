package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.AdminSummaryResponse;
import org.learning.mldsa.dtos.InstitutionResponse;
import org.learning.mldsa.dtos.PageResponse;
import org.learning.mldsa.dtos.SettlementMovementResponse;
import org.learning.mldsa.dtos.SettlementPositionResponse;
import org.learning.mldsa.dtos.SettlementRefusalResponse;
import org.learning.mldsa.dtos.SettlementResponse;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.AccountType;
import org.learning.mldsa.models.FileTransfer;
import org.learning.mldsa.models.Payment;
import org.learning.mldsa.models.PaymentStatus;
import org.learning.mldsa.models.Role;
import org.learning.mldsa.models.SettlementMessage;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.AccountBalance;
import org.learning.mldsa.repositories.AccountRepository;
import org.learning.mldsa.repositories.FileTransferRepository;
import org.learning.mldsa.repositories.InstitutionBalance;
import org.learning.mldsa.repositories.InstitutionCount;
import org.learning.mldsa.repositories.PaymentRepository;
import org.learning.mldsa.repositories.PostingRepository;
import org.learning.mldsa.repositories.SettlementMessageRepository;
import org.learning.mldsa.repositories.UserRepositories;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The read model behind the Central Bank's console.
 *
 * Read-only by design. This role supervises the system rather than operating on customers'
 * money: there is no method here that moves a balance, alters a payment, or touches a card.
 * What the Central Bank can create — institutions and other overseers — lives with the rest of
 * provisioning in UserService rather than here.
 *
 * What it returns about institutions is aggregated. Supervision needs to know how much an
 * institution's customers hold between them, not who they are.
 */
@RequiredArgsConstructor
@Service
public class AdminService {

    private final UserRepositories userRepositories;
    private final AccountRepository accountRepository;
    private final PostingRepository postingRepository;
    private final PaymentRepository paymentRepository;
    private final FileTransferRepository fileTransferRepository;
    private final SettlementMessageRepository settlementMessageRepository;

    @Value("${app.ledger.default-currency}")
    private String currency;

    @Value("${app.settlement.net-debit-cap}")
    private BigDecimal netDebitCap;

    /**
     * A page of institutions, each with its aggregates.
     *
     * Each figure is one grouped query for the whole page rather than one per institution, so
     * the console doesn't get slower as the number of institutions it supervises grows.
     */
    public Page<InstitutionResponse> listInstitutions(Pageable pageable) {
        Page<User> institutions = userRepositories.findByRoleOrderByInstitutionCodeAscUserIdAsc(
                Role.INSTITUTION, pageable);

        List<Long> ids = institutions.map(User::getUserId).getContent();
        if (ids.isEmpty()) {
            // Keeps the total, so a client asking past the last page can step back.
            return new PageImpl<>(List.of(), pageable, institutions.getTotalElements());
        }

        Map<Long, String> settlementAccounts = accountRepository
                .findByInstitution_UserIdInAndType(ids, AccountType.SETTLEMENT).stream()
                .collect(Collectors.toMap(account -> account.getInstitution().getUserId(),
                        Account::getAccountNumber));
        Map<Long, Long> customerCounts = accountRepository.countPerInstitution(AccountType.CUSTOMER, ids).stream()
                .collect(Collectors.toMap(InstitutionCount::institutionId, InstitutionCount::count));
        Map<Long, BigDecimal> customerFunds = balancesPerInstitution(AccountType.CUSTOMER, ids);
        Map<Long, BigDecimal> positions = balancesPerInstitution(AccountType.SETTLEMENT, ids);

        return institutions.map(institution -> {
            Long id = institution.getUserId();
            return new InstitutionResponse(
                    id,
                    institution.getName(),
                    institution.getInstitutionCode(),
                    institution.getInstitutionNumber(),
                    settlementAccounts.get(id),
                    currency,
                    customerCounts.getOrDefault(id, 0L),
                    // Absent from a grouped result means no postings yet: zero, not unknown.
                    customerFunds.getOrDefault(id, BigDecimal.ZERO),
                    positions.getOrDefault(id, BigDecimal.ZERO)
            );
        });
    }

    /**
     * Where every institution stands, and the movements that put them there.
     *
     * Positions are read from the ledger rather than stored, exactly like a customer's
     * balance, so the sum below cannot drift from the postings it describes. Movements are
     * derived by comparing each completed payment's two institutions — the ledger already
     * knows where an account is held, and a flag duplicating that could disagree with it.
     */
    public SettlementResponse settlement(Pageable pageable) {
        List<Account> settlementAccounts = accountRepository.findByType(AccountType.SETTLEMENT);
        Map<Long, BigDecimal> balances = balancesOf(settlementAccounts);

        List<SettlementPositionResponse> positions = settlementAccounts.stream()
                .map(account -> {
                    User institution = account.getInstitution();
                    BigDecimal position = balances.getOrDefault(account.getAccountId(), BigDecimal.ZERO);
                    return new SettlementPositionResponse(
                            institution.getUserId(),
                            institution.getName(),
                            institution.getInstitutionCode(),
                            institution.getInstitutionNumber(),
                            account.getAccountNumber(),
                            position,
                            netDebitCap,
                            // A payment is refused once it would take the position below
                            // -cap, so what remains is the position plus the cap.
                            position.add(netDebitCap));
                })
                .sorted(Comparator.comparing(SettlementPositionResponse::getInstitutionCode))
                .toList();

        BigDecimal positionsSum = positions.stream()
                .map(SettlementPositionResponse::getPosition)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Page<Payment> interBankPayments = paymentRepository.findInterBankCompleted(pageable);
        List<Long> paymentIds = interBankPayments.map(Payment::getPaymentId).getContent();
        // One query for the page's interbank messages, so a movement can be traced to the
        // instruction that carried it without a lookup per row.
        Map<Long, Long> messageIds = paymentIds.isEmpty() ? Map.of()
                : settlementMessageRepository.findByPayment_PaymentIdIn(paymentIds).stream()
                        .collect(Collectors.toMap(
                                message -> message.getPayment().getPaymentId(),
                                SettlementMessage::getMessageId));

        return new SettlementResponse(
                positions,
                positionsSum,
                positionsSum.signum() == 0,
                currency,
                PageResponse.of(interBankPayments,
                        payment -> toMovement(payment, messageIds.get(payment.getPaymentId()))));
    }

    /** Payments a bank could not settle, with the cause the customer was not told. */
    public Page<SettlementRefusalResponse> settlementRefusals(Pageable pageable) {
        return paymentRepository.findSettlementRefusals(pageable).map(payment -> {
            User institution = payment.getFromAccount().getInstitution();
            return new SettlementRefusalResponse(
                    payment.getPaymentId(),
                    institution.getName(),
                    institution.getInstitutionCode(),
                    payment.getAmount(),
                    payment.getFailureReason(),
                    payment.getFailureDetail(),
                    payment.getCreatedAt());
        });
    }

    public Page<FileTransfer> listTransfers(Pageable pageable) {
        return fileTransferRepository.findAllByOrderBySentAtDescTransferIdDesc(pageable);
    }

    public AdminSummaryResponse summary() {
        Map<Role, Long> byRole = userRepositories.findAll().stream()
                .collect(Collectors.groupingBy(User::getRole, Collectors.counting()));

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

        // Invariant I2, checked rather than assumed: every inter-bank payment debits one
        // settlement account and credits another, so these can only ever sum to zero.
        BigDecimal settlementPositionsSum = balancesOf(accountRepository.findByType(AccountType.SETTLEMENT))
                .values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new AdminSummaryResponse(
                byRole.getOrDefault(Role.NORMAL_USER, 0L),
                byRole.getOrDefault(Role.INSTITUTION, 0L),
                byRole.getOrDefault(Role.BANK, 0L),
                accountRepository.countByType(AccountType.CUSTOMER),
                totalHeld,
                currency,
                completed,
                payments.size() - completed,
                completedVolume,
                settlementPositionsSum,
                settlementPositionsSum.signum() == 0,
                transfers.size(),
                transfers.stream().filter(t -> t.getStoredXmlFilename() != null).count()
        );
    }

    private static SettlementMovementResponse toMovement(Payment payment, Long messageId) {
        User from = payment.getFromAccount().getInstitution();
        User to = payment.getToAccount().getInstitution();
        return new SettlementMovementResponse(
                payment.getPaymentId(),
                messageId,
                payment.getTransactionRef(),
                from.getName(),
                from.getInstitutionCode(),
                to.getName(),
                to.getInstitutionCode(),
                payment.getAmount(),
                payment.getCreatedAt());
    }

    private Map<Long, BigDecimal> balancesOf(List<Account> accounts) {
        List<Long> accountIds = accounts.stream().map(Account::getAccountId).toList();
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return postingRepository.sumBalancesOf(accountIds).stream()
                .collect(Collectors.toMap(AccountBalance::accountId, AccountBalance::balance));
    }

    private Map<Long, BigDecimal> balancesPerInstitution(AccountType type, Collection<Long> institutionIds) {
        return postingRepository.sumBalancesPerInstitution(type, institutionIds).stream()
                .collect(Collectors.toMap(InstitutionBalance::institutionId, InstitutionBalance::balance));
    }
}
