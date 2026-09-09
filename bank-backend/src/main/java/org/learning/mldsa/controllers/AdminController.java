package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.AdminAccountResponse;
import org.learning.mldsa.dtos.PageResponse;
import org.learning.mldsa.dtos.PageRequestParams;
import org.learning.mldsa.dtos.AdminSummaryResponse;
import org.learning.mldsa.dtos.FileTransferResponse;
import org.learning.mldsa.dtos.PaymentResponse;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.FileTransfer;
import org.learning.mldsa.models.Payment;
import org.learning.mldsa.services.AdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The Bank role's oversight console.
 *
 * The whole controller is restricted to that one role, declared once at the class rather
 * than repeated per method — every route here reads across accounts that belong to other
 * people, so there is no endpoint on it that should ever be reachable by anyone else.
 *
 * Read-only: nothing here changes a balance, a payment or a card. Oversight watches; it
 * does not reach into customers' money.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('BANK')")
public class AdminController {

    private final AdminService adminService;

    /** Platform totals, including how much is being refused rather than only what succeeds. */
    @GetMapping("/summary")
    ResponseEntity<AdminSummaryResponse> summary() {
        return ResponseEntity.ok(adminService.summary());
    }

    @GetMapping("/accounts")
    ResponseEntity<List<AdminAccountResponse>> accounts() {
        return ResponseEntity.ok(adminService.listAccounts());
    }

    /** Every payment, refused ones included, with the reason each was refused. */
    @GetMapping("/payments")
    ResponseEntity<PageResponse<PaymentResponse>> payments(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEntity.ok(PageResponse.of(
                adminService.listPayments(PageRequestParams.of(page, size)),
                AdminController::toResponse));
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

    private static PaymentResponse toResponse(Payment payment) {
        Account to = payment.getToAccount();
        return new PaymentResponse(
                payment.getPaymentId(),
                payment.getFromAccount().getAccountNumber(),
                to == null ? null : to.getAccountNumber(),
                payment.getAmount(),
                payment.getDescription(),
                payment.getStatus(),
                payment.getFailureReason(),
                payment.getTransactionRef(),
                payment.getCreatedAt()
        );
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
                transfer.getStoredXmlFilename() != null
        );
    }
}
