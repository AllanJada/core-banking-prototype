package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.FileTransferResponse;
import org.learning.mldsa.dtos.SlipLineItem;
import org.learning.mldsa.dtos.SlipRequest;
import org.learning.mldsa.services.FileTransferService;
import org.learning.mldsa.services.PdfGenerationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Generates a payslip PDF from structured field data and sends it in a single request.
 *
 * Deliberately takes a JSON body of plain fields (SlipRequest) rather than a file upload —
 * there is no point at which a client supplies PDF bytes directly. The PDF is rendered
 * entirely server-side (PdfGenerationService) and those exact rendered bytes are what get
 * hashed and signed (FileTransferService.sendGeneratedFile), with no opportunity for a
 * client to substitute or alter file content between "compose" and "send".
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/slips")
public class SlipController {

    private final PdfGenerationService pdfGenerationService;
    private final FileTransferService fileTransferService;

    // senderId is the authenticated caller's own id (see FileTransferController's comment on
    // the same change) rather than a field on the request body — SlipRequest no longer has a
    // senderId field at all, so there's nothing left for a client to spoof here either.
    @PostMapping("/send")
    ResponseEntity<FileTransferResponse> generateAndSend(@RequestBody SlipRequest request,
                                                           @AuthenticationPrincipal Long senderId) {
        byte[] pdfBytes = pdfGenerationService.generateSlipPdf(request);

        String filename = buildFilename(request);
        // Passing the slip's own earnings/deductions through lets FileTransferService attach
        // a zero-knowledge compliance proof of the net pay automatically -- see
        // FileTransferService#attachComplianceProof. Best-effort: this send succeeds the same
        // way whether or not that proof generation succeeds.
        FileTransferResponse response = fileTransferService.sendGeneratedFile(
                senderId, request.getReceiverId(), pdfBytes, filename,
                extractAmounts(request.getEarnings()), extractAmounts(request.getDeductions())
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private String buildFilename(SlipRequest request) {
        String base = request.getTitle() != null && !request.getTitle().isBlank()
                ? request.getTitle()
                : "Slip";
        // Keep it filesystem-friendly for display purposes; the actual stored filename on
        // disk is always a server-generated UUID regardless (see FileStorageService).
        String safe = base.replaceAll("[^a-zA-Z0-9 _-]", "").trim();
        return (safe.isEmpty() ? "Slip" : safe) + ".pdf";
    }

    // Mirrors SlipRequest.sum()'s own null-filtering. Returns an empty (never null) list for
    // a null items list, distinct from the null earnings/deductions that FileTransferService
    // treats as "not a payslip, skip proof generation" -- a payslip with a genuinely empty
    // earnings or deductions section should still get a proof attempted, not skipped.
    private List<BigDecimal> extractAmounts(List<SlipLineItem> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .map(SlipLineItem::getAmount)
                .filter(Objects::nonNull)
                .toList();
    }
}
