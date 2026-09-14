package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.PaymentPreviewResponse;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.AccountType;
import org.learning.mldsa.models.FileTransfer;
import org.learning.mldsa.models.Payment;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an approved payslip into money.
 *
 * A slip has always carried an ISO 20022 pain.001 describing a credit transfer — the employer's
 * account debited, the employee's credited, for the net pay — and until this existed, nothing
 * acted on that description. The document said money would move and no money moved, which made
 * the account numbers on it decorative and the payload a statement of intent rather than an
 * instruction.
 *
 * Two rules make it real:
 *
 * **The money comes from the signed payload, not from a separate copy.** The amount and both
 * account numbers are parsed out of the stored XML *after* its integrity has been re-verified,
 * so what moves is exactly what was signed. There is no second record of the amount that could
 * drift from the document.
 *
 * **The accounts must belong to the two institutions exchanging the slip.** The payer must be a
 * customer of the sending bank and the payee a customer of the receiving one. Anything else is
 * an instruction one bank has no standing to give: a slip cannot debit an account at a bank
 * that never saw it, and sending a slip to a bank that does not hold the payee is asking the
 * wrong institution to pay.
 */
@RequiredArgsConstructor
@Service
public class SlipDisbursementService {

    private final AccountRepository accountRepository;
    private final PaymentService paymentService;
    private final Pain001GenerationService pain001GenerationService;

    /**
     * Checks a slip could be disbursed, before it is rendered, signed and sent.
     *
     * Fail-fast only — the authoritative check is the one at approval, which resolves the
     * accounts again from the signed payload. Doing it here as well means a slip naming an
     * account that does not exist is refused while its author is still looking at the form,
     * rather than days later in someone else's inbox.
     */
    public void requireDisbursable(String payerAccountNumber, String payeeAccountNumber,
                                   User sender, User receiver) {
        requireCustomerAccountAt(payerAccountNumber, sender, "payer");
        requireCustomerAccountAt(payeeAccountNumber, receiver, "payee");
    }

    /**
     * Executes the instruction the transfer carries.
     *
     * Runs inside the approving transaction, so the decision and the money commit together: a
     * disbursement that cannot be made — insufficient funds, a cap, a closed settlement
     * position — takes the approval down with it and leaves the transfer awaiting review. The
     * refusal is still recorded as a FAILED payment, so the paying institution can see why
     * its payroll did not go out.
     *
     * @param payloadXml the stored pain.001, already re-verified against what was signed
     */
    @Transactional
    public Payment disburse(FileTransfer transfer, byte[] payloadXml) {
        PaymentPreviewResponse.PayloadPreview instruction = pain001GenerationService.parsePreview(payloadXml);

        Account payer = requireCustomerAccountAt(
                instruction.getDebtorAccountNumber(), transfer.getSender(), "payer");
        Account payee = requireCustomerAccountAt(
                instruction.getCreditorAccountNumber(), transfer.getReceiver(), "payee");

        return paymentService.disburse(payer, payee, instruction.getAmount(), describe(instruction));
    }

    /** What the postings say on both statements. */
    private String describe(PaymentPreviewResponse.PayloadPreview instruction) {
        String remittance = instruction.getRemittanceInformation();
        return remittance == null || remittance.isBlank() ? "Payroll disbursement" : remittance.trim();
    }

    /**
     * Resolves an account number to a customer account held at the given institution.
     *
     * All three conditions are part of one lookup rather than checks applied afterwards: the
     * number must exist, name a CUSTOMER account (never a settlement position), and be held at
     * that institution.
     */
    private Account requireCustomerAccountAt(String accountNumber, User institution, String party) {
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new RuntimeException("The slip has no " + party + " account number");
        }
        return accountRepository.findByAccountNumber(accountNumber.trim())
                .filter(account -> account.getType() == AccountType.CUSTOMER)
                .filter(account -> account.getInstitution().getUserId().equals(institution.getUserId()))
                .orElseThrow(() -> new RuntimeException(
                        "The " + party + " account " + accountNumber.trim()
                                + " is not a customer account at " + institution.getName()));
    }
}
