package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.UserRequest;
import org.learning.mldsa.dtos.UserResponse;
import org.learning.mldsa.models.Role;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.UserRepositories;
import org.learning.mldsa.security.AuthenticatedUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyPair;
import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class UserService {
    private final UserRepositories userRepositories;
    private final PasswordEncoder passwordEncoder;
    private final CryptoService cryptoService;
    private final AccountService accountService;

    /**
     * Registers an account and, for customers, opens their banking account alongside it.
     *
     * Transactional because registration now writes to two modules' tables: a customer
     * left with a login but no account — or an account with no owner — would be a state
     * nothing in the system knows how to repair.
     */
    @Transactional
    public UserResponse createNewUser(UserRequest request, AuthenticatedUser caller) {
        requireMayProvision(caller);

        if (userRepositories.existsByName(request.getUsername())) {
            throw new RuntimeException("User already exists");
        }

        User user = new User();
        user.setName(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(request.getRole() != null ? request.getRole() : Role.NORMAL_USER);
        // Normalised to uppercase: BICs are case-insensitive by definition but compared as
        // strings here, so storing one shape avoids two spellings of the same institution.
        user.setBic(request.getBic() == null || request.getBic().isBlank()
                ? null
                : request.getBic().trim().toUpperCase());

        // Every account gets its own Ed25519 key pair at registration, regardless of
        // role. The private key is used later to sign files this account sends; the
        // public key is handed to recipients (implicitly, via lookup) to verify those
        // signatures.
        KeyPair keyPair = cryptoService.generateSigningKeyPair();
        user.setPublicKey(cryptoService.encodePublicKey(keyPair.getPublic()));
        user.setPrivateKey(cryptoService.encodePrivateKey(keyPair.getPrivate()));

        User savedUser = userRepositories.save(user);

        // Only customers hold a money account. Institutions and the bank role take part in
        // file transfer and oversight respectively, neither of which has a balance.
        if (savedUser.getRole() == Role.NORMAL_USER) {
            accountService.openAccountFor(savedUser);
        }

        return new UserResponse(savedUser.getUserId(), savedUser.getName(), savedUser.getRole());

    }

    /**
     * Decides whether the caller may provision an account.
     *
     * Anonymous creation is allowed only while the system has no Bank account at all —
     * otherwise there would be no way to create the first one, since the role that
     * authorises provisioning would not yet exist. Once a Bank account exists that door
     * closes for good, and provisioning belongs to the console.
     */
    private void requireMayProvision(AuthenticatedUser caller) {
        if (caller != null && caller.role() == Role.BANK) {
            return;
        }
        if (!userRepositories.existsByRole(Role.BANK)) {
            return;
        }
        throw new AccessDeniedException("Only a bank operator can create accounts");
    }

    public List<UserResponse> listUsers() {
        return userRepositories.findAll().stream()
                .map(u -> new UserResponse(u.getUserId(), u.getName(), u.getRole()))
                .collect(Collectors.toList());
    }

    /**
     * The accounts a given role is allowed to send files to. Institutions exchange files
     * with other institutions, so a sender should only ever be offered those.
     *
     * This exists because the recipient picker was previously filtered in the browser
     * only — the API happily listed every account to everyone. Filtering here means the
     * dropdown and the rule the backend actually enforces (see
     * FileTransferService.signAndPersist) come from the same place.
     */
    public List<UserResponse> listCounterparties(Long requestingUserId, Role role) {
        return userRepositories.findAll().stream()
                .filter(u -> u.getRole() == role)
                .filter(u -> !u.getUserId().equals(requestingUserId))
                .map(u -> new UserResponse(u.getUserId(), u.getName(), u.getRole()))
                .collect(Collectors.toList());
    }

}
