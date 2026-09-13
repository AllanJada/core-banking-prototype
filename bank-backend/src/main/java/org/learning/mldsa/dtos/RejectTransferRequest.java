package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The reason a recipient is refusing a transfer, required so the sender has something to act on. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RejectTransferRequest {
    private String reason;
}
