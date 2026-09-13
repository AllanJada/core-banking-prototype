package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.learning.mldsa.models.Role;

/** The bootstrap request that creates the system's first Central Bank overseer. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRequest {
    private String username;
    private String password;
    // Optional, and BANK is the only value accepted: bootstrap creates an overseer and
    // refuses any other role outright rather than silently substituting one.
    private Role role;
}
