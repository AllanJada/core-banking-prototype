package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.learning.mldsa.models.CardStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A customer as their own institution sees them.
 *
 * Only ever built for a customer the calling institution holds — see InstitutionService.
 * Like the rest of the system it carries no key material, no PIN and no full card number.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CustomerResponse {
    private Long userId;
    private String username;
    private String accountNumber;
    private String currency;
    private BigDecimal balance;
    private Instant openedAt;
    /** Null when the account has no card issued. */
    private CardStatus cardStatus;
    /** Masked to the last four digits, or null when there is no card. */
    private String cardNumber;
}
