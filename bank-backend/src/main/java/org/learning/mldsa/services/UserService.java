package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.UserRequest;
import org.learning.mldsa.dtos.UserResponse;
import org.learning.mldsa.models.User;
import org.learning.mldsa.models.UserType;
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
        // Optional on the request — institution accounts were the only account type
        // before bank_dashboard, so an absent userType (older or unaware callers)
        // still resolves to INSTITUTION rather than null.
        user.setUserType(request.getUserType() != null ? request.getUserType() : UserType.INSTITUTION);

        // Every institution gets its own ML-DSA-65 key pair at registration. The private
        // key is used later to sign files this institution sends; the public key is
        // handed to recipients (implicitly, via lookup) to verify those signatures.
        KeyPair dsaKeyPair = cryptoService.generateMlDsaKeyPair();
        user.setPublicKey(cryptoService.encodePublicKey(dsaKeyPair.getPublic()));
        user.setPrivateKey(cryptoService.encodePrivateKey(dsaKeyPair.getPrivate()));

        // And its own ML-KEM-768 key pair — senders encapsulate against this institution's
        // kemPublicKey to derive the AES key that encrypts files addressed to it; only this
        // institution's kemPrivateKey can decapsulate that back to the same key. Generated
        // unconditionally, regardless of userType: a BANK account that never ends up
        // sending/receiving encrypted files just has an unused key pair, which costs
        // nothing at runtime, versus branching this logic by type and risking it being
        // the one thing that's missing if a BANK account's role expands later.
        KeyPair kemKeyPair = cryptoService.generateMlKemKeyPair();
        user.setKemPublicKey(cryptoService.encodeKemPublicKey(kemKeyPair.getPublic()));
        user.setKemPrivateKey(cryptoService.encodeKemPrivateKey(kemKeyPair.getPrivate()));

        User savedUser = userRepositories.save(user);
        return new UserResponse(savedUser.getUserId(), savedUser.getName(), savedUser.getUserType());
    }

    public List<UserResponse> listUsers() {
        return userRepositories.findAll().stream()
                .map(u -> new UserResponse(u.getUserId(), u.getName(), u.getUserType()))
                .collect(Collectors.toList());
    }
}
