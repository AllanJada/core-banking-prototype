package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.UserRequest;
import org.learning.mldsa.dtos.UserResponse;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.UserRepositories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class UserService {
    private final UserRepositories userRepositories;
    private final PasswordEncoder passwordEncoder;
    private final CryptoService cryptoService;

    public UserResponse createNewUser(UserRequest request) {
        if (userRepositories.existsByName(request.getUsername())) {
            throw new RuntimeException("User already exists");
        }

        User user = new User();
        user.setName(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        // Every institution gets its own ML-DSA-65 key pair at registration. The private
        // key is used later to sign files this institution sends; the public key is
        // handed to recipients (implicitly, via lookup) to verify those signatures.
        KeyPair keyPair = cryptoService.generateMlDsaKeyPair();
        user.setPublicKey(cryptoService.encodePublicKey(keyPair.getPublic()));
        user.setPrivateKey(cryptoService.encodePrivateKey(keyPair.getPrivate()));

        User savedUser = userRepositories.save(user);

        return new UserResponse(savedUser.getUserId(), savedUser.getName());

    }

    public List<UserResponse> listUsers() {
        return userRepositories.findAll().stream()
                .map(u -> new UserResponse(u.getUserId(), u.getName()))
                .collect(Collectors.toList());
    }

}
