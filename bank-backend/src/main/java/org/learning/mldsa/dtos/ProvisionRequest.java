package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Credentials for an account created by the tier above it — a Central Bank overseer, or an
 * institution's customer.
 *
 * Deliberately carries no role. Which role gets created is decided by the endpoint the
 * request was sent to, each guarded by its own @PreAuthorize, so there is no field a caller
 * could set to ask for more than that endpoint grants.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProvisionRequest {
    private String username;
    private String password;
}
