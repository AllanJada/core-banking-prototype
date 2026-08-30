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
    // Optional — see UserService.createNewUser for the INSTITUTION fallback when omitted.
    private UserType userType;
}
