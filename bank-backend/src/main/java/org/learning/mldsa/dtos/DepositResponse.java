package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DepositResponse {
    private Long depositId;
    private String accountNumber;
    private BigDecimal amount;
    private String description;
    private String transactionRef;
    /** The institution's signature over the deposit, so the receipt can be checked later. */
    private String signature;
    private Instant depositedAt;
    /** The customer's balance once this deposit was posted — what a teller reads back. */
    private BigDecimal balanceAfter;
}
