package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.learning.mldsa.models.UserType;

/**
 * What POST /api/v1/auth/login returns: the same user fields UserResponse always carried,
 * plus the bearer token the frontend must now attach (Authorization: Bearer <token>) to every
 * other request. Kept as its own DTO rather than adding a token field onto UserResponse, since
 * UserResponse is also used for endpoints (registration, listUsers) that have nothing to do
 * with the caller's own session and shouldn't carry a token field at all.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private Long userId;
    private String username;
    private UserType userType;
    private String token;
}
