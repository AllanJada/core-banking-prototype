package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.learning.mldsa.models.PaymentLinkStatus;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PaymentLinkResponse {
    private String linkId;
    /** The account that will be paid — needed by whoever is deciding whether to pay it. */
    private String requesterAccountNumber;
    private BigDecimal amount;
    private String description;
    /** The status as it stands now: reports EXPIRED for a link whose deadline has passed. */
    private PaymentLinkStatus status;
    private Instant expiresAt;
    private Instant paidAt;
    private Instant createdAt;
}
