package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.learning.mldsa.models.UserType;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRequest {
    private String username;
    private String password;
    // Optional — defaults to INSTITUTION in UserService if omitted, so the existing
    // registration calls that don't send this field keep working unchanged.
    private UserType userType;
}
