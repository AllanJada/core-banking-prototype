package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.learning.mldsa.models.Role;

/**
 * What a successful login returns: the bearer token the client must send back on every
 * subsequent request, plus the account details the frontend needs to decide which
 * dashboard to render.
 *
 * The role is echoed here for the frontend's routing convenience only. It is also inside
 * the signed token, and that copy — not this one — is what the backend authorizes
 * against, so editing the role in browser storage changes what the UI draws and nothing
 * about what the API will actually permit.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private Long userId;
    private String username;
    private Role role;
}
