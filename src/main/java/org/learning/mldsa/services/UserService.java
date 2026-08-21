package org.learning.mldsa.services;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.UserRequest;
import org.learning.mldsa.dtos.UserResponse;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.UserRepositories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class UserService {
    private final UserRepositories userRepositories;
    private final PasswordEncoder passwordEncoder;

    public UserResponse createNewUser(UserRequest request) {
        if (userRepositories.existsByName(request.getUsername())) {
            throw new RuntimeException("User already exists");
        }

        User user = new User();
        user.setName(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        User savedUser = userRepositories.save(user);

        return new UserResponse(savedUser.getUserId(), savedUser.getName());

    }

}
