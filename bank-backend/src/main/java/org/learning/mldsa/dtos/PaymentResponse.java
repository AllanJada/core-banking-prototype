package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.learning.mldsa.models.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {
    private Long paymentId;
    private String fromAccountNumber;
    /** Null when the payment was refused because no such recipient existed. */
    private String toAccountNumber;
    private BigDecimal amount;
    private String description;
    private PaymentStatus status;
    /** Set only on a refused payment, explaining why. */
    private String failureReason;
    private String transactionRef;
    private Instant createdAt;
}
