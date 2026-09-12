package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.FileTransferResponse;
import org.learning.mldsa.dtos.PageResponse;
import org.learning.mldsa.dtos.PageRequestParams;
import org.learning.mldsa.dtos.PaymentPreviewResponse;
import org.learning.mldsa.dtos.RejectTransferRequest;
import org.learning.mldsa.security.AuthenticatedUser;
import org.learning.mldsa.services.FileDownload;
import org.learning.mldsa.services.FileTransferService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * File exchange between institutions.
 *
 * Restricted to the Institution role: under the role model this system now uses, Bank is
 * an oversight role rather than a file-transfer participant, and its read access across
 * all transfers belongs in the admin console rather than here.
 *
 * Note that no endpoint takes a userId any more — the acting account comes from the
 * verified token, so a caller can only ever read their own inbox or send as themselves.
 * Previously these were request parameters, which meant any client could read any
 * account's mail by changing a number in the URL.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/files")
@PreAuthorize("hasRole('INSTITUTION')")
public class FileTransferController {

    private final FileTransferService fileTransferService;

    @PostMapping(value = "/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<FileTransferResponse> sendFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("receiverId") Long receiverId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        FileTransferResponse response = fileTransferService.sendFile(user.userId(), receiverId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * A page of received transfers, newest first.
     *
     * page and size are optional; omitting them gives the first page at the default size,
     * so a caller that does not care about paging still gets a bounded response rather than
     * the whole mailbox.
     */
    @GetMapping("/inbox")
    ResponseEntity<PageResponse<FileTransferResponse>> getInbox(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ResponseEntity.ok(fileTransferService.getInbox(user.userId(), PageRequestParams.of(page, size)));
    }

    @GetMapping("/outbox")
    ResponseEntity<PageResponse<FileTransferResponse>> getOutbox(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ResponseEntity.ok(fileTransferService.getOutbox(user.userId(), PageRequestParams.of(page, size)));
    }

    /**
     * The review step: structured key fields for a recipient deciding whether to approve or
     * reject, re-verified against the stored bytes on every call rather than a cached
     * verdict from send time. Never throws on a failed check — a transfer that no longer
     * verifies is exactly the case a reviewer needs to see, not a 400 that hides it.
     */
    @GetMapping("/{transferId}/preview")
    ResponseEntity<PaymentPreviewResponse> preview(
            @PathVariable Long transferId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ResponseEntity.ok(fileTransferService.preview(transferId, user.userId()));
    }

    /**
     * The document itself, rendered inline for review rather than saved as an attachment.
     * Available at any status, including after a decision has been made, so a recipient can
     * re-open what they already approved or rejected.
     */
    @GetMapping("/{transferId}/preview/document")
    ResponseEntity<Resource> previewDocument(
            @PathVariable Long transferId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        FileDownload preview = fileTransferService.previewDocument(transferId, user.userId());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + preview.originalFilename() + "\"")
                .body(preview.resource());
    }

    /** Accepts a transfer, the decision that makes it downloadable. */
    @PostMapping("/{transferId}/approve")
    ResponseEntity<FileTransferResponse> approve(
            @PathVariable Long transferId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ResponseEntity.ok(fileTransferService.approve(transferId, user.userId()));
    }

    /** Refuses a transfer. Terminal, and requires a reason. */
    @PostMapping("/{transferId}/reject")
    ResponseEntity<FileTransferResponse> reject(
            @PathVariable Long transferId,
            @RequestBody RejectTransferRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ResponseEntity.ok(fileTransferService.reject(transferId, user.userId(), request.getReason()));
    }

    /**
     * The transfer's ISO 20022 payload, for a recipient whose systems parse the payment
     * rather than reading the document.
     */
    @GetMapping("/{transferId}/payload")
    ResponseEntity<Resource> downloadPayload(
            @PathVariable Long transferId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        FileDownload download = fileTransferService.downloadPayload(transferId, user.userId());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.originalFilename() + "\"")
                .body(download.resource());
    }

    @GetMapping("/{transferId}/download")
    ResponseEntity<Resource> downloadFile(
            @PathVariable Long transferId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        FileDownload download = fileTransferService.downloadFile(transferId, user.userId());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.originalFilename() + "\"")
                .body(download.resource());
    }
}
