package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * The at-a-glance figures for the oversight console.
 *
 * Deliberately includes refused payments alongside completed ones. A count of what
 * succeeded flatters the system; what is being refused, and how often, is the more useful
 * signal for anyone watching it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AdminSummaryResponse {
    private long customers;
    private long institutions;
    private long bankOperators;
    private long accounts;
    /** Sum of every account balance — what the ledger says the platform is holding. */
    private BigDecimal totalHeld;
    private String currency;
    private long completedPayments;
    private long failedPayments;
    private BigDecimal completedPaymentVolume;
    private long fileTransfers;
    /** Transfers carrying an ISO 20022 payload, of the total above. */
    private long transfersWithPayload;
}
