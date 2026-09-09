package org.learning.mldsa.models;

/**
 * The three account roles the system recognises, and the basis for every access
 * decision made in this application (see SecurityConfig and the @PreAuthorize
 * annotations on each controller).
 *
 * This replaces the earlier two-value UserType enum, which only ever decided which
 * dashboard the frontend rendered. A role now genuinely gates what an account may
 * call, so adding a value here means deciding what that role is permitted to do,
 * not just where it lands after login.
 */
public enum Role {

    /** Retail banking customer: owns an account, makes deposits and payments. */
    NORMAL_USER,

    /** Another financial institution: the file-transfer counterparty this project began as. */
    INSTITUTION,

    /** Operator of this system: oversight across accounts and transfers, not a file-transfer participant. */
    BANK
}
