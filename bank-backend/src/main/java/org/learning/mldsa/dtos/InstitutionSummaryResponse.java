package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * An institution's own overview: its customers in aggregate, and where it stands at the
 * Central Bank.
 *
 * The same figures the Central Bank sees for this institution, plus the cap and headroom it
 * needs in order to know why its customers' inter-bank payments might start being refused.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InstitutionSummaryResponse {
    private Long institutionId;
    private String institutionName;
    private String institutionCode;
    private String institutionNumber;
    private String currency;
    private long customerCount;
    /** Sum of this institution's customers' balances — what it holds for them. */
    private BigDecimal customerFundsHeld;
    private String settlementAccountNumber;
    /** Negative means this institution is a net debtor to the rest of the system. */
    private BigDecimal settlementPosition;
    private BigDecimal netDebitCap;
    /** How much further its customers can pay other banks before refusals start. */
    private BigDecimal headroom;
}
