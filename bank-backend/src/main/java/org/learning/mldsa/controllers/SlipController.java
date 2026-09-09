package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.FileTransferResponse;
import org.learning.mldsa.dtos.SlipRequest;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.UserRepositories;
import org.learning.mldsa.security.AuthenticatedUser;
import org.learning.mldsa.services.FileTransferService;
import org.learning.mldsa.services.Iso20022Payload;
import org.learning.mldsa.services.Pain001GenerationService;
import org.learning.mldsa.services.PdfGenerationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

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
@PreAuthorize("hasRole('INSTITUTION')")
public class SlipController {

    private final PdfGenerationService pdfGenerationService;
    private final Pain001GenerationService pain001GenerationService;
    private final FileTransferService fileTransferService;
    private final UserRepositories userRepositories;

    /**
     * Generates the slip, its ISO 20022 payload, and sends both under one signature.
     *
     * The order matters. The payload is built and schema-validated first, so a slip that
     * cannot produce a valid pain.001 fails before anything is stored or signed — rather
     * than leaving a signed PDF on record beside a payment instruction that was rejected.
     */
    @PostMapping("/send")
    ResponseEntity<FileTransferResponse> generateAndSend(
            @RequestBody SlipRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        User sender = userRepositories.findById(user.userId())
                .orElseThrow(() -> new RuntimeException("Sender not found"));
        User receiver = userRepositories.findById(request.getReceiverId())
                .orElseThrow(() -> new RuntimeException("Receiver not found"));

        // Minted here, at origination, and then used by both the payload and the transfer.
        String uetr = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();

        byte[] xmlBytes = pain001GenerationService.generatePain001(request, sender, receiver, uetr, createdAt);
        byte[] pdfBytes = pdfGenerationService.generateSlipPdf(request);

        String filename = buildFilename(request);
        FileTransferResponse response = fileTransferService.sendGeneratedFile(
                user.userId(), request.getReceiverId(), pdfBytes, filename,
                new Iso20022Payload(uetr, xmlBytes, stripExtension(filename) + ".xml")
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
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
}
