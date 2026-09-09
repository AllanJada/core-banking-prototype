package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * The payer is taken from the request's token, so only the recipient is named here — a
 * request cannot instruct a payment out of an account it does not hold.
 *
 * The recipient is identified by account number, the identifier a customer would actually
 * be given, rather than by an internal user or account id.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequest {
    private String toAccountNumber;
    private BigDecimal amount;
    private String description;
}
