package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.learning.mldsa.models.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment made by one of the institution's own customers.
 *
 * Distinct from the customer's own PaymentResponse because this view answers a different
 * question — which of our customers paid what, and why it was refused. It is the one place
 * the specific cause of a settlement refusal is shown alongside the customer who hit it,
 * which the §6 visibility table allows an institution for its own customers and nobody
 * else's.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InstitutionPaymentResponse {
    private Long paymentId;
    private String customerUsername;
    private String fromAccountNumber;
    /** Null when the payment was refused because no payable recipient existed. */
    private String toAccountNumber;
    /** The receiving bank's code, or null when the recipient could not be resolved. */
    private String toInstitutionCode;
    /** Whether the money had to cross banks — four postings rather than two. */
    private boolean interBank;
    private BigDecimal amount;
    private String description;
    private PaymentStatus status;
    /** The reason as the customer was told it. */
    private String failureReason;
    /** The specific cause behind a settlement refusal, which the customer was not told. */
    private String failureDetail;
    private String transactionRef;
    private Instant createdAt;
}
