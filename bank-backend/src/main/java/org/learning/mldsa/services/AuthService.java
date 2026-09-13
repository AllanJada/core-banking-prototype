package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.LoginRequest;
import org.learning.mldsa.dtos.LoginResponse;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.UserRepositories;
import org.learning.mldsa.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class AuthService {

    private final UserRepositories userRepositories;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * Authenticates an account and issues its bearer token.
     *
     * There is deliberately one login path for all three roles: the account's role decides
     * what it can reach afterwards, never which door it came through. The unknown-username
     * and wrong-password cases return the same message on purpose, so the response can't be
     * used to work out which usernames exist.
     */
    public LoginResponse login(LoginRequest request) {
        User user = userRepositories.findByName(request.getUsername())
                .orElseThrow(() -> new RuntimeException("Invalid username or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid username or password");
        }

        String token = jwtService.issueToken(user);

        return new LoginResponse(token, user.getUserId(), user.getName(), user.getRole());
    }
}
