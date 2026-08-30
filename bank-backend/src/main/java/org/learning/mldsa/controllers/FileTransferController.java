package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.FileTransferResponse;
import org.learning.mldsa.services.FileDownload;
import org.learning.mldsa.services.FileTransferService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/files")
public class FileTransferController {

    private final FileTransferService fileTransferService;

    // senderId/userId below no longer come from the request — each is the authenticated
    // caller's own id, bound from the validated JWT by JwtAuthenticationFilter and exposed
    // here via @AuthenticationPrincipal. Before this fix, any caller (the endpoint was also
    // permitAll() until SecurityConfig was hardened) could pass any senderId/userId and act
    // as a different institution — read another institution's inbox, or send a file "as" one.
    // See the earlier "how can this be solved" discussion (OWASP API1:2023, Broken Object
    // Level Authorization) for the full reasoning.

    @PostMapping(value = "/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<FileTransferResponse> sendFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("receiverId") Long receiverId,
            @AuthenticationPrincipal Long senderId
    ) {
        FileTransferResponse response = fileTransferService.sendFile(senderId, receiverId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/inbox")
    ResponseEntity<List<FileTransferResponse>> getInbox(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(fileTransferService.getInbox(userId));
    }

    @GetMapping("/outbox")
    ResponseEntity<List<FileTransferResponse>> getOutbox(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(fileTransferService.getOutbox(userId));
    }

    @GetMapping("/{transferId}/download")
    ResponseEntity<Resource> downloadFile(
            @PathVariable Long transferId,
            @AuthenticationPrincipal Long userId
    ) {
        FileDownload download = fileTransferService.downloadFile(transferId, userId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.originalFilename() + "\"")
                .body(download.resource());
    }
}
