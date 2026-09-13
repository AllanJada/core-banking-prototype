package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * The Central Bank's view of settlement: where every institution stands, and what moved.
 *
 * Positions come as a whole list rather than a page, because there is one per licensed
 * institution and they are read together — their sum is the point. Movements are paged, since
 * they only accumulate.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SettlementResponse {
    private List<SettlementPositionResponse> positions;

    /**
     * Sum of every settlement position. Invariant I2 says this is always zero: each
     * inter-bank payment debits one settlement account and credits another by the same
     * amount, so the positions can only ever redistribute, never create or destroy value.
     */
    private BigDecimal positionsSum;

    /** Whether that sum is in fact zero — the I2 check, computed on every read. */
    private boolean balanced;

    private String currency;
    private PageResponse<SettlementMovementResponse> movements;
}
