package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * The requester is taken from the token, so a link always pays into the caller's own
 * account — there is no field here that could direct someone else's money elsewhere.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentLinkRequest {
    private BigDecimal amount;
    private String description;
    /** Optional; defaults to the configured maximum lifetime when omitted. */
    private Long expiresInHours;
}
