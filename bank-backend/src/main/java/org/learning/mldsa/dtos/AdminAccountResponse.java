package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.learning.mldsa.models.CardStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An account as the Bank role sees it: the customer, the balance, and whether a card is
 * attached.
 *
 * Distinct from the customer's own AccountResponse because this view answers a different
 * question — who holds this account — and so carries the owner. It still carries no key
 * material, no PIN, and no full card number: an oversight role needs to see that an account
 * exists and what it holds, not the secrets belonging to it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AdminAccountResponse {
    private Long accountId;
    private String accountNumber;
    private String ownerUsername;
    private String currency;
    private BigDecimal balance;
    private Instant openedAt;
    /** Null when the account has no card issued. */
    private CardStatus cardStatus;
    /** Masked to the last four digits, or null when there is no card. */
    private String cardNumber;
}
