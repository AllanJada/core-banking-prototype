package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * An institution as the Central Bank sees it: aggregates only.
 *
 * Carries how many customers the institution has and how much they hold between them, but
 * never who they are or what any one of them holds. Supervision is of the institution;
 * customer identities stay with the customer's own bank.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InstitutionResponse {
    private Long userId;
    private String username;
    private String institutionCode;
    /** The three digits that prefix every account and card number this institution issues. */
    private String institutionNumber;
    private String settlementAccountNumber;
    private String currency;
    private long customerCount;
    /** Sum of this institution's customer balances, derived from the ledger. */
    private BigDecimal customerFundsHeld;
    /** The settlement account's balance. Negative means a net debtor to the system. */
    private BigDecimal settlementPosition;
}
