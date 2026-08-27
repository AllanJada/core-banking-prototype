package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
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
    private String fileHash;
    private String signature;
    private Boolean signatureValid;
}
