package org.learning.mldsa.dtos;

import java.util.List;

/**
 * Request body for POST /api/v1/compliance/prove.
 *
 * Deliberately independent of SlipRequest/SlipLineItem: this endpoint is a standalone demo
 * of the zero-knowledge compliance-proof capability, not part of the payslip-send flow, so
 * it takes plain cent amounts rather than reusing banking-system DTOs.
 */
public class ComplianceProveRequest {

    private List<Long> earningsCents;
    private List<Long> deductionsCents;

    public ComplianceProveRequest() {
    }

    public ComplianceProveRequest(List<Long> earningsCents, List<Long> deductionsCents) {
        this.earningsCents = earningsCents;
        this.deductionsCents = deductionsCents;
    }

    public List<Long> getEarningsCents() {
        return earningsCents;
    }

    public void setEarningsCents(List<Long> earningsCents) {
        this.earningsCents = earningsCents;
    }

    public List<Long> getDeductionsCents() {
        return deductionsCents;
    }

    public void setDeductionsCents(List<Long> deductionsCents) {
        this.deductionsCents = deductionsCents;
    }
}
