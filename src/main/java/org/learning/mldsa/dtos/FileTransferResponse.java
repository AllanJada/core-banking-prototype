package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.Instant;

@NoArgsConstructor
@AllArgsConstructor
public class FileTransferResponse {
    private Long transferId;
    private String senderUsername;
    private String receiverUsername;
    private String originalFilename;
    private String status;
    private Instant sentAt;
    private Instant downloadedAt;
}
