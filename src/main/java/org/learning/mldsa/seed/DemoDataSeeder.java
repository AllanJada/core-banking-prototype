package org.learning.mldsa.seed;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.UserRepositories;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Demo-only convenience: seeds a fixed set of users on startup so there's no registration
 * flow to build yet. Passwords are still bcrypt-hashed at rest, per the "not a priority,
 * but not reckless either" scope agreed for this demo.
 */
@RequiredArgsConstructor
@Component
public class DemoDataSeeder implements CommandLineRunner {

    private static final String DEMO_PASSWORD = "password123";
    private static final String[] DEMO_USERNAMES = {"bank-alpha", "bank-beta", "bank-gamma"};

    private final UserRepositories userRepositories;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        for (String username : DEMO_USERNAMES) {
            if (!userRepositories.existsByName(username)) {
                User user = new User();
                user.setName(username);
                user.setPassword(passwordEncoder.encode(DEMO_PASSWORD));
                userRepositories.save(user);
            }
        }
    }
}
