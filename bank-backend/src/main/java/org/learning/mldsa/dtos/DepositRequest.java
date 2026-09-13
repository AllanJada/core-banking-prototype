package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * No account field: a customer deposits into their own account, which is resolved from
 * the request's token. Naming the target would let a caller credit someone else.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DepositRequest {
    private BigDecimal amount;
    private String description;
}
