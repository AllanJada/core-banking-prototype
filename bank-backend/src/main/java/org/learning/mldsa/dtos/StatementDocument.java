package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Everything printed on a statement, already formatted for display.
 *
 * Amounts and dates arrive here as strings rather than as BigDecimal and Instant on
 * purpose: formatting money and dates is a decision (how many decimals, which separator,
 * which timezone) and making it in Java keeps it in one place, testable, and out of the
 * template. It also keeps the template free of any dependency on a Thymeleaf temporal
 * dialect.
 */
@Getter
@AllArgsConstructor
public class StatementDocument {

    private final String accountNumber;
    private final String accountHolder;
    private final String currency;
    private final String periodFrom;
    private final String periodTo;
    private final String generatedAt;
    private final String openingBalance;
    private final String closingBalance;
    private final String totalCredits;
    private final String totalDebits;

    /**
     * The exact instant string that was signed, printed so a verifier can reconstruct the
     * signed envelope from the page rather than having to guess a format or timezone.
     */
    private final String generatedAtIso;

    /** Ed25519 signature over the statement's figures, made with the account owner's key. */
    private final String signature;

    private final List<StatementLine> lines;

    /**
     * One row of a statement.
     *
     * Debit and credit are separate columns, with the unused one left blank, the way a
     * statement is conventionally read — rather than one signed column the reader has to
     * interpret. The running balance is carried on each line so the arithmetic can be
     * followed down the page.
     */
    @Getter
    @AllArgsConstructor
    public static class StatementLine {
        private final String date;
        private final String description;
        private final String debit;
        private final String credit;
        private final String balance;
    }
}
