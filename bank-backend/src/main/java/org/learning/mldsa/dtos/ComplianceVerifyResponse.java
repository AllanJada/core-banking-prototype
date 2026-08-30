package org.learning.mldsa.dtos;

/** Response body for POST /api/v1/compliance/verify. */
public class ComplianceVerifyResponse {

    private boolean valid;
    private String error;

    public ComplianceVerifyResponse() {
    }

    public ComplianceVerifyResponse(boolean valid, String error) {
        this.valid = valid;
        this.error = error;
    }

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
