package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment refused because the paying institution could not settle it.
 *
 * This is the other half of the split the customer sees: they are told only that the payment
 * could not be settled, while the institution and the Central Bank get the specific cause
 * here. Still institution-level — the customer who attempted it is not named.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SettlementRefusalResponse {
    private Long paymentId;
    private String institutionName;
    private String institutionCode;
    private BigDecimal amount;
    /** The generic reason the customer was given. */
    private String reason;
    /** The specific cause, which the customer was not given. */
    private String detail;
    private Instant refusedAt;
}
