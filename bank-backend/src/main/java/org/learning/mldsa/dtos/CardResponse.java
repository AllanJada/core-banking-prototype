package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.learning.mldsa.models.CardStatus;

import java.time.LocalDate;

/**
 * A card as it is safe to hand back: masked, with no PIN material of any kind.
 *
 * The full number appears exactly once, in the response to issuing the card, and is masked
 * everywhere afterwards — so a compromised session token cannot be used to read out a card
 * number that was issued before it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CardResponse {
    /** Masked as **** **** **** 1234, except in the issuance response. */
    private String cardNumber;
    private String accountNumber;
    private LocalDate expiresOn;
    /** Reports EXPIRED for a card past its date, even though the stored value is ACTIVE. */
    private CardStatus status;
}
