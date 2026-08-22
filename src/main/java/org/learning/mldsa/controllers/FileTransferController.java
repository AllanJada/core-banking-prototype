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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/files")
public class FileTransferController {

    private final FileTransferService fileTransferService;

    @PostMapping(value = "/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<FileTransferResponse> sendFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("senderId") Long senderId,
            @RequestParam("receiverId") Long receiverId
    ) {
        FileTransferResponse response = fileTransferService.sendFile(senderId, receiverId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/inbox")
    ResponseEntity<List<FileTransferResponse>> getInbox(@RequestParam("userId") Long userId) {
        return ResponseEntity.ok(fileTransferService.getInbox(userId));
    }

    @GetMapping("/outbox")
    ResponseEntity<List<FileTransferResponse>> getOutbox(@RequestParam("userId") Long userId) {
        return ResponseEntity.ok(fileTransferService.getOutbox(userId));
    }

    @GetMapping("/{transferId}/download")
    ResponseEntity<Resource> downloadFile(
            @PathVariable Long transferId,
            @RequestParam("userId") Long userId
    ) {
        FileDownload download = fileTransferService.downloadFile(transferId, userId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.originalFilename() + "\"")
                .body(download.resource());
    }
}
