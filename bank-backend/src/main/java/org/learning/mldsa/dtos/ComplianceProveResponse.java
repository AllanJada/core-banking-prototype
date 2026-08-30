package org.learning.mldsa.dtos;

/**
 * Response body for POST /api/v1/compliance/prove.
 *
 * proofBase64 is the only thing a verifier needs later (together with netPayCents and
 * numEntries) to check the claim -- the individual earnings/deductions line items that
 * produced it are never included here or anywhere else in this response.
 */
public class ComplianceProveResponse {

    private long netPayCents;
    private int numEntries;
    private String proofBase64;
    private int proofSizeBytes;

    public ComplianceProveResponse() {
    }

    public ComplianceProveResponse(long netPayCents, int numEntries, String proofBase64, int proofSizeBytes) {
        this.netPayCents = netPayCents;
        this.numEntries = numEntries;
        this.proofBase64 = proofBase64;
        this.proofSizeBytes = proofSizeBytes;
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

    public String getProofBase64() {
        return proofBase64;
    }

    public void setProofBase64(String proofBase64) {
        this.proofBase64 = proofBase64;
    }

    public int getProofSizeBytes() {
        return proofSizeBytes;
    }

    public void setProofSizeBytes(int proofSizeBytes) {
        this.proofSizeBytes = proofSizeBytes;
    }
}
