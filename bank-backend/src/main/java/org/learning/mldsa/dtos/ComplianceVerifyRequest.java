package org.learning.mldsa.dtos;

/** Request body for POST /api/v1/compliance/verify. */
public class ComplianceVerifyRequest {

    private String proofBase64;
    private long netPayCents;
    private int numEntries;

    public ComplianceVerifyRequest() {
    }

    public ComplianceVerifyRequest(String proofBase64, long netPayCents, int numEntries) {
        this.proofBase64 = proofBase64;
        this.netPayCents = netPayCents;
        this.numEntries = numEntries;
    }

    public String getProofBase64() {
        return proofBase64;
    }

    public void setProofBase64(String proofBase64) {
        this.proofBase64 = proofBase64;
    }

    public long getNetPayCents() {
        return netPayCents;
    }

    public void setNetPayCents(long netPayCents) {
        this.netPayCents = netPayCents;
    }

    public int getNumEntries() {
        return numEntries;
    }

    public void setNumEntries(int numEntries) {
        this.numEntries = numEntries;
    }
}
