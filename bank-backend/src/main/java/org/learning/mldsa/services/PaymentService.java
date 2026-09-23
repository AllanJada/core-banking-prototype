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
    private final SettlementMessageService settlementMessageService;

    @Value("${app.payments.max-per-transaction}")
    private BigDecimal maxPerTransaction;

    @Value("${app.payments.max-per-day}")
    private BigDecimal maxPerDay;

    @Value("${app.settlement.net-debit-cap}")
    private BigDecimal netDebitCap;

    /**
     * Takes a deposit at the counter: the institution's till is debited and its customer's
     * account credited, as one two-sided movement.
     *
     * The caller is responsible for having established that this account is one of this
     * institution's own customers — InstitutionService resolves it within the signed-in
     * institution before calling here, so another bank's customer is never reached.
     *
     * The per-transaction and daily caps deliberately do not apply: they exist to bound what
     * can leave a <em>customer's</em> account, and this only adds to one. The till is allowed
     * to go negative — that balance is what the institution has put into circulation, and
     * making it visible is the point of funding deposits from an account at all.
     */
    @Transactional
    public Deposit depositByInstitution(User institution, Account account,
                                        BigDecimal amount, String description) {
        requireWellFormedAmount(amount);

        Account till = accountService.requireCashAccount(institution.getUserId());
        Instant depositedAt = Instant.now();

        String envelope = cryptoService.buildTellerDepositEnvelope(
                account.getAccountNumber(), institution.getInstitutionCode(), amount,
                depositedAt.toEpochMilli());
        String signature = cryptoService.sign(envelope, cryptoService.signingKeyOf(institution));

        String transactionRef = accountService.transfer(till, account, amount, description);

        Deposit deposit = new Deposit();
        deposit.setAccount(account);
        deposit.setInstitution(institution);
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
        return execute(from, to, amount, description);
    }

    /**
     * Moves money between two accounts the caller has already resolved.
     *
     * This is the path a payroll disbursement takes: the payer is named by a signed document
     * rather than by whoever holds the session, so it cannot be derived from a token the way
     * pay() does. Everything after that point is deliberately the same code — the caps, the
     * overdraft rule, the settlement routing, the signature and the interbank message — so a
     * slip cannot become a second way to move money with its own subtly different rules.
     */
    @Transactional
    public Payment disburse(Account from, Account to, BigDecimal amount, String description) {
        requireWellFormedAmount(amount);
        return execute(from, to, amount, description);
    }

    private Payment execute(Account from, Account to, BigDecimal amount, String description) {
        if (to.getAccountId().equals(from.getAccountId())) {
            throw reject(from, to, amount, description, "An account cannot pay itself");
        }
        if (amount.compareTo(maxPerTransaction) > 0) {
            throw reject(from, to, amount, description,
                    "Amount exceeds the per-transaction limit of " + maxPerTransaction);
        }

        // Everything from here reads the payer's position and then decides on it, so the row
        // is locked first. Without this, two payments submitted at the same instant both read
        // the balance before either had posted, both found it sufficient, and both went
        // through — leaving the account overdrawn on a system that offers no overdraft. The
        // checks above need no lock: neither of them reads anything that another payment
        // could be changing underneath them.
        //
        // This is the same treatment the paying bank's settlement account already gets one
        // level down, for the same reason and in the same order: payer first, then their
        // bank's position. Every path that moves money arrives here, so that order is the
        // only order in which these two rows are ever taken.
        lockForUpdate(from);

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

        Payment saved = paymentRepository.save(payment);

        // The interbank leg: a payment that crossed banks is also an instruction from one bank
        // to the other, written as an ISO 20022 pacs.008. Inside this same transaction, so
        // money and the message instructing it commit together — and a message that cannot be
        // generated or schema-validated takes the payment down with it rather than leaving a
        // settled transfer nobody can evidence.
        if (interBank) {
            settlementMessageService.record(saved, from, to);
        }

        return saved;
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

    /**
     * Takes the payer's row for the rest of the transaction, so another payment from the same
     * account waits rather than reading a balance that is about to change.
     *
     * The returned row is discarded: this is called for the lock, not the data. The account is
     * already loaded, and re-reading it here would only invite the two copies to disagree.
     */
    private void lockForUpdate(Account account) {
        accountRepository.findByIdForUpdate(account.getAccountId())
                .orElseThrow(() -> new RuntimeException("Account no longer exists"));
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
