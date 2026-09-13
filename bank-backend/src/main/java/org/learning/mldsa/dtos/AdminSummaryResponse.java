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
    /** Customer accounts only: a settlement account is an institution's position, not customer money. */
    private long accounts;
    /** Sum of every account balance — what the ledger says the platform is holding. */
    private BigDecimal totalHeld;
    private String currency;
    private long completedPayments;
    private long failedPayments;
    private BigDecimal completedPaymentVolume;
    /**
     * Sum of every institution's settlement position, and whether it is zero — invariant I2,
     * checked on every read rather than assumed. A non-zero sum would mean an inter-bank
     * payment had moved one position without the matching other side.
     */
    private BigDecimal settlementPositionsSum;
    private boolean settlementBalanced;

    private long fileTransfers;
    /** Transfers carrying an ISO 20022 payload, of the total above. */
    private long transfersWithPayload;
}
