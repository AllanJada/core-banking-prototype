package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.LoginRequest;
import org.learning.mldsa.dtos.UserResponse;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.UserRepositories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class AuthService {

    private final UserRepositories userRepositories;
    private final PasswordEncoder passwordEncoder;

    public UserResponse login(LoginRequest request) {
        User user = userRepositories.findByName(request.getUsername())
                .orElseThrow(() -> new RuntimeException("Invalid username or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid username or password");
        }

        return new UserResponse(user.getUserId(), user.getName(), user.getUserType());
    }
}
