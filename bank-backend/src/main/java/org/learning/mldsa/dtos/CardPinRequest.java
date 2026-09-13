package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Carries PIN material for issuing a card, verifying a PIN, or changing one.
 *
 * currentPin is used only when changing a PIN; the single-PIN operations use pin. Kept as
 * one request type so there is one place to remember that none of these values may ever be
 * logged or echoed back in a response.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CardPinRequest {
    private String pin;
    private String currentPin;
}
