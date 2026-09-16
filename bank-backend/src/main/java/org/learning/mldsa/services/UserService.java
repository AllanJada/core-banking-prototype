package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.InstitutionRequest;
import org.learning.mldsa.dtos.ProvisionRequest;
import org.learning.mldsa.dtos.UserRequest;
import org.learning.mldsa.dtos.UserResponse;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.Role;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.UserRepositories;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Provisioning, along a chain of custody: every account is created by the tier directly
 * above it. The Central Bank creates institutions and other overseers, and an institution
 * creates its own customers — so there is always an identifiable party responsible for an
 * account's existence.
 *
 * Each tier has its own method, called from its own role-guarded endpoint, rather than one
 * method whose permitted effect depends on a role named in the request.
 */
@RequiredArgsConstructor
@Service
public class UserService {

    private static final Pattern INSTITUTION_CODE = Pattern.compile("[A-Z0-9]{3,8}");

    // Bank numbers run 100-999: three digits, never with a leading zero, so the prefix on an
    // account number is always the same width and can be read straight off it.
    private static final int INSTITUTION_NUMBER_ORIGIN = 100;
    private static final int INSTITUTION_NUMBER_BOUND = 1000;
    private static final int MAX_INSTITUTION_NUMBER_ATTEMPTS = 20;

    private final UserRepositories userRepositories;
    private final PasswordEncoder passwordEncoder;
    private final CryptoService cryptoService;
    private final AccountService accountService;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Whether no Central Bank overseer exists yet — the only state in which bootstrap is allowed. */
    public boolean isBootstrapOpen() {
        return !userRepositories.existsByRole(Role.BANK);
    }

    /**
     * Creates the system's first Central Bank overseer, and nothing else.
     *
     * Anonymous creation is allowed only while no BANK account exists, since until then there
     * is nobody who could authorise one. The window produces an overseer only: an institution
     * created here would exist before any supervisor did. A request naming another role is
     * refused rather than quietly turned into an overseer, so a client can't mistake what it
     * got. Once an overseer exists the window closes for good.
     */
    @Transactional
    public UserResponse bootstrap(UserRequest request) {
        if (!isBootstrapOpen()) {
            throw new AccessDeniedException("The system is already provisioned");
        }
        if (request.getRole() != null && request.getRole() != Role.BANK) {
            throw new RuntimeException("The first account must be a Central Bank overseer");
        }
        return toResponse(userRepositories.save(
                newUser(request.getUsername(), request.getPassword(), Role.BANK)));
    }

    /** Creates another Central Bank overseer, on an existing overseer's authority. */
    @Transactional
    public UserResponse createOverseer(ProvisionRequest request) {
        return toResponse(userRepositories.save(
                newUser(request.getUsername(), request.getPassword(), Role.BANK)));
    }

    /**
     * Licenses an institution and opens its settlement account.
     *
     * One transaction for all three: an institution without a settlement account would have
     * nowhere for an inter-bank payment to settle, and one without a cash account could take
     * no deposits. Nothing in the system would know how to repair either.
     *
     * @return the settlement account, whose owner and institution are the new institution
     */
    @Transactional
    public Account createInstitution(InstitutionRequest request) {
        String code = requireWellFormedInstitutionCode(request.getInstitutionCode());
        if (userRepositories.existsByInstitutionCode(code)) {
            throw new RuntimeException("An institution with that code already exists");
        }

        User institution = newUser(request.getUsername(), request.getPassword(), Role.INSTITUTION);
        institution.setInstitutionCode(code);
        institution.setInstitutionNumber(generateUniqueInstitutionNumber());
        // Normalised to uppercase: BICs are case-insensitive by definition but compared as
        // strings here, so storing one shape avoids two spellings of the same institution.
        institution.setBic(request.getBic() == null || request.getBic().isBlank()
                ? null
                : request.getBic().trim().toUpperCase());

        User saved = userRepositories.save(institution);
        // Both accounts in the same transaction as the institution itself, for the same
        // reason: one with nowhere to settle could not be paid across banks, and one with no
        // till could not take a deposit. Neither is repairable from outside this method.
        accountService.openCashAccount(saved);
        return accountService.openSettlementAccount(saved);
    }

    /**
     * Creates a customer and opens their account at the calling institution.
     *
     * The institution comes from the caller's token, passed down by the controller, and never
     * from the request body — an institution cannot open a customer at another bank by naming
     * it. Login and account are written in one transaction, so a customer can never end up
     * with one and not the other.
     *
     * @return the customer's new account
     */
    @Transactional
    public Account createCustomer(Long institutionId, ProvisionRequest request) {
        User institution = userRepositories.findById(institutionId)
                .filter(user -> user.getRole() == Role.INSTITUTION)
                .orElseThrow(() -> new AccessDeniedException("Only an institution can create customers"));

        User customer = userRepositories.save(
                newUser(request.getUsername(), request.getPassword(), Role.NORMAL_USER));
        return accountService.openCustomerAccount(customer, institution);
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
                .map(UserService::toResponse)
                .collect(Collectors.toList());
    }

    /** An unsaved account with its credentials and signing keys, after the checks every tier shares. */
    private User newUser(String username, String password, Role role) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new RuntimeException("A username and password are both required");
        }
        String name = username.trim();
        if (userRepositories.existsByName(name)) {
            throw new RuntimeException("User already exists");
        }

        User user = new User();
        user.setName(name);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(role);

        // Every account gets its own Ed25519 key pair at registration, regardless of
        // role. The private key is used later to sign what this account produces; the
        // public key is handed to recipients (implicitly, via lookup) to verify those
        // signatures.
        KeyPair keyPair = cryptoService.generateSigningKeyPair();
        user.setPublicKey(cryptoService.encodePublicKey(keyPair.getPublic()));
        user.setPrivateKey(cryptoService.encodePrivateKey(keyPair.getPrivate()));

        return user;
    }

    /**
     * Allocates the institution's bank number.
     *
     * Collisions are re-rolled rather than fatal, and the retries are bounded so a system
     * that has genuinely run out of numbers says so instead of looping forever — the same
     * shape account and card number allocation uses.
     */
    private String generateUniqueInstitutionNumber() {
        for (int attempt = 0; attempt < MAX_INSTITUTION_NUMBER_ATTEMPTS; attempt++) {
            String candidate = String.valueOf(INSTITUTION_NUMBER_ORIGIN
                    + secureRandom.nextInt(INSTITUTION_NUMBER_BOUND - INSTITUTION_NUMBER_ORIGIN));
            if (!userRepositories.existsByInstitutionNumber(candidate)) {
                return candidate;
            }
        }
        throw new RuntimeException("Could not allocate an institution number");
    }

    private String requireWellFormedInstitutionCode(String code) {
        String normalised = code == null ? "" : code.trim().toUpperCase();
        if (!INSTITUTION_CODE.matcher(normalised).matches()) {
            throw new RuntimeException("Institution code must be 3 to 8 letters or digits");
        }
        return normalised;
    }

    private static UserResponse toResponse(User user) {
        return new UserResponse(user.getUserId(), user.getName(), user.getRole());
    }
}
