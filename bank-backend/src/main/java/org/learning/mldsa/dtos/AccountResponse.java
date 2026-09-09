package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An account as the customer sees it.
 *
 * The balance here is computed from the account's postings at the moment this response is
 * built — it is a reading of the ledger, not a stored field being echoed back.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AccountResponse {
    private String accountNumber;
    private String currency;
    private BigDecimal balance;
    private Instant openedAt;
}
