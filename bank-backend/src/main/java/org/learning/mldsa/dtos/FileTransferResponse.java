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

    /** The transfer's end-to-end reference; null for transfers predating it. */
    private String uetr;

    /**
     * Whether an ISO 20022 payload accompanies the document.
     *
     * A boolean rather than the payload's hash: the client only needs to know whether there
     * is one to offer, and the hash is an integrity value the server checks itself.
     */
    private boolean hasPayload;

    /** When the recipient approved or rejected this transfer; null while still SENT. */
    private Instant reviewedAt;

    /** Why the recipient rejected this transfer; null unless status is REJECTED. */
    private String rejectionReason;
}
