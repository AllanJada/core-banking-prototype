package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.learning.mldsa.models.PostingDirection;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One line of an account's history.
 *
 * Direction and a positive amount are reported separately, exactly as stored, rather than
 * being flattened into a signed number — the client decides how to present a debit, and
 * the wire format keeps matching the ledger it came from.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PostingResponse {
    private Long postingId;
    private PostingDirection direction;
    private BigDecimal amount;
    private String description;
    private String transactionRef;
    private Instant postedAt;
}
