package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One institution's settlement position, as the Central Bank supervises it.
 *
 * Institution-level throughout: a position is what a bank owes or is owed by the rest of the
 * system, and says nothing about which of its customers moved it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SettlementPositionResponse {
    private Long institutionId;
    private String institutionName;
    private String institutionCode;
    private String institutionNumber;
    private String settlementAccountNumber;
    /** Negative means a net debtor to the rest of the system, which is normal between runs. */
    private BigDecimal position;
    private BigDecimal netDebitCap;
    /** How much further this institution can settle outwards before payments are refused. */
    private BigDecimal headroom;
}
