package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.AdminSummaryResponse;
import org.learning.mldsa.dtos.FileTransferResponse;
import org.learning.mldsa.dtos.InstitutionRequest;
import org.learning.mldsa.dtos.InstitutionResponse;
import org.learning.mldsa.dtos.PageRequestParams;
import org.learning.mldsa.dtos.PageResponse;
import org.learning.mldsa.dtos.ProvisionRequest;
import org.learning.mldsa.dtos.SettlementMessageResponse;
import org.learning.mldsa.dtos.SettlementRefusalResponse;
import org.learning.mldsa.dtos.SettlementResponse;
import org.learning.mldsa.dtos.UserResponse;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.FileTransfer;
import org.learning.mldsa.models.SettlementMessage;
import org.learning.mldsa.models.User;
import org.learning.mldsa.services.AdminService;
import org.learning.mldsa.services.SettlementMessageService;
import org.learning.mldsa.services.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.function.Function;

/**
 * The Central Bank's console.
 *
 * The whole controller is restricted to the BANK role, declared once at the class rather
 * than repeated per method — every route here either supervises institutions or provisions
 * them, and neither should ever be reachable by anyone else.
 *
 * Its only writes are provisioning the tier directly below it (institutions) and other
 * overseers. Nothing here changes a balance, a payment or a card, and nothing here creates a
 * customer: customers belong to their institution.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('BANK')")
public class AdminController {

    private final AdminService adminService;
    private final UserService userService;
    private final SettlementMessageService settlementMessageService;

    /** Platform totals, including how much is being refused rather than only what succeeds. */
    @GetMapping("/summary")
    ResponseEntity<AdminSummaryResponse> summary() {
        return ResponseEntity.ok(adminService.summary());
    }

    /** Licenses an institution, opening its settlement account in the same transaction. */
    @PostMapping("/institutions")
    ResponseEntity<InstitutionResponse> createInstitution(@RequestBody InstitutionRequest request) {
        Account settlement = userService.createInstitution(request);
        User institution = settlement.getInstitution();
        return ResponseEntity.status(HttpStatus.CREATED).body(new InstitutionResponse(
                institution.getUserId(),
                institution.getName(),
                institution.getInstitutionCode(),
                institution.getInstitutionNumber(),
                settlement.getAccountNumber(),
                settlement.getCurrency(),
                // A newly licensed institution has no customers and no settlement movements.
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));
    }

    /** Institutions with their aggregates. No customer identities or individual balances. */
    @GetMapping("/institutions")
    ResponseEntity<PageResponse<InstitutionResponse>> institutions(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEntity.ok(PageResponse.of(
                adminService.listInstitutions(PageRequestParams.of(page, size)),
                Function.identity()));
    }

    /** Creates another Central Bank overseer. */
    @PostMapping("/overseers")
    ResponseEntity<UserResponse> createOverseer(@RequestBody ProvisionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createOverseer(request));
    }

    /**
     * Settlement: every institution's position, whether they still sum to zero, and the
     * bank-to-bank movements behind them.
     *
     * This replaced the earlier listing of every payment, which showed the Central Bank one
     * customer paying another. Supervision is of institutions; who paid whom inside a bank
     * stays with that bank.
     */
    @GetMapping("/settlement")
    ResponseEntity<SettlementResponse> settlement(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEntity.ok(adminService.settlement(PageRequestParams.of(page, size)));
    }

    /**
     * Every ISO 20022 interbank message, as settlement operator.
     *
     * The Central Bank sees both sides of each instruction — it is the system the money
     * settles across — where an institution sees only its own.
     */
    @GetMapping("/settlement/messages")
    ResponseEntity<PageResponse<SettlementMessageResponse>> settlementMessages(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEntity.ok(PageResponse.of(
                settlementMessageService.listAll(PageRequestParams.of(page, size)),
                Function.identity()));
    }

    /** The pacs.008 itself, hash and signature re-checked before it is served. */
    @GetMapping("/settlement/messages/{messageId}/xml")
    ResponseEntity<byte[]> settlementMessageXml(@PathVariable Long messageId) {
        SettlementMessage message = settlementMessageService.require(messageId);
        return InstitutionController.xmlResponse(message, settlementMessageService.loadVerified(message));
    }

    /** Payments a bank could not settle, with the cause its customer was not given. */
    @GetMapping("/settlement/refusals")
    ResponseEntity<PageResponse<SettlementRefusalResponse>> settlementRefusals(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEntity.ok(PageResponse.of(
                adminService.settlementRefusals(PageRequestParams.of(page, size)),
                Function.identity()));
    }

    /** Every file transfer between institutions, across all participants. */
    @GetMapping("/transfers")
    ResponseEntity<PageResponse<FileTransferResponse>> transfers(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEntity.ok(PageResponse.of(
                adminService.listTransfers(PageRequestParams.of(page, size)),
                AdminController::toResponse));
    }

    /**
     * Reuses the same shape the participants' own inbox returns.
     *
     * Including the signature and its validity on purpose: whether transfers are verifying
     * is exactly what an oversight view exists to notice.
     */
    private static FileTransferResponse toResponse(FileTransfer transfer) {
        return new FileTransferResponse(
                transfer.getTransferId(),
                transfer.getSender().getName(),
                transfer.getReceiver().getName(),
                transfer.getOriginalFilename(),
                transfer.getStatus().name(),
                transfer.getSentAt(),
                transfer.getDownloadedAt(),
                transfer.getFileHash(),
                transfer.getSignature(),
                transfer.getSignatureValid(),
                transfer.getUetr(),
                transfer.getStoredXmlFilename() != null,
                transfer.getReviewedAt(),
                transfer.getRejectionReason(),
                // Whether approving this document actually moved money, which is exactly the
                // kind of thing an oversight view exists to notice.
                transfer.getPayment() == null ? null : transfer.getPayment().getPaymentId(),
                transfer.getPayment() == null ? null : transfer.getPayment().getAmount()
        );
    }
}
