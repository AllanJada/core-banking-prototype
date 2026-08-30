package org.learning.mldsa.models;

/**
 * Which side of the system an account belongs to. INSTITUTION is the original,
 * only account type before the bank_dashboard feature — the file-transfer/slip-
 * composing accounts already in production use. BANK is the newer account type
 * with its own login/dashboard.
 */
public enum UserType {
    INSTITUTION,
    BANK
}
