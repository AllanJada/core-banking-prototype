package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.CardStatus;
import org.learning.mldsa.models.DebitCard;
import org.learning.mldsa.repositories.DebitCardRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Issuing debit cards and verifying cardholders by PIN.
 *
 * The PIN is the only thing that authenticates a card operation here, which is what makes
 * how it is stored the important decision: it is bcrypt-hashed on the way in and compared
 * by hashing the attempt, never by reading anything back. Nothing in this class can produce
 * a PIN, only confirm one.
 */
@RequiredArgsConstructor
@Service
public class CardService {

    private static final int CARD_NUMBER_LENGTH = 16;
    private static final char CARD_LEADING_DIGIT = '4';
    private static final int MAX_CARD_NUMBER_ATTEMPTS = 10;
    private static final int PIN_LENGTH = 4;

    private final DebitCardRepository debitCardRepository;
    private final AccountService accountService;
    private final PasswordEncoder passwordEncoder;
    private final CardPinAttemptRecorder pinAttemptRecorder;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.cards.validity-years}")
    private int validityYears;

    /**
     * Issues a card against the caller's account.
     *
     * One card per account: a second one would need a way to say which card an operation
     * meant, and nothing here needs that yet. Replacing a card is therefore blocking the
     * old one first, which is also how a real replacement works.
     */
    @Transactional
    public DebitCard issue(Long userId, String pin) {
        requireWellFormedPin(pin);

        Account account = accountService.requireAccountFor(userId);
        debitCardRepository.findByAccount_AccountId(account.getAccountId()).ifPresent(existing -> {
            throw new RuntimeException("This account already has a card");
        });

        LocalDate expiresOn = LocalDate.now()
                .plusYears(validityYears)
                // Cards expire at the end of their month, as printed on a real one.
                .withDayOfMonth(1)
                .plusMonths(1)
                .minusDays(1);

        DebitCard card = new DebitCard();
        card.setCardNumber(generateUniqueCardNumber(account));
        card.setAccount(account);
        card.setExpiresOn(expiresOn);
        card.setStatus(CardStatus.ACTIVE);
        card.setPinHash(passwordEncoder.encode(pin));
        card.setFailedPinAttempts(0);
        card.setIssuedAt(Instant.now());

        return debitCardRepository.save(card);
    }

    public Optional<DebitCard> findCardFor(Long userId) {
        Account account = accountService.requireAccountFor(userId);
        return debitCardRepository.findByAccount_AccountId(account.getAccountId());
    }

    public DebitCard requireCardFor(Long userId) {
        return findCardFor(userId)
                .orElseThrow(() -> new RuntimeException("No card has been issued for this account"));
    }

    /**
     * Checks a PIN, counting failures and locking the card at the limit.
     *
     * The count is kept by CardPinAttemptRecorder in its own transaction, because reporting
     * a wrong PIN means throwing, and that rolls back whatever transaction this ran in. Were
     * the counter updated here it would be rolled back with it, and the card would never
     * lock however many PINs were tried.
     *
     * The response says only whether the PIN was right, never how many attempts remain.
     */
    public void verifyPin(Long userId, String pin) {
        DebitCard card = requireCardFor(userId);
        requireUsable(card);

        if (!passwordEncoder.matches(pin == null ? "" : pin, card.getPinHash())) {
            boolean nowBlocked = pinAttemptRecorder.recordFailure(card.getCardId());
            throw new RuntimeException(nowBlocked
                    ? "Incorrect PIN. This card is now blocked after too many attempts"
                    : "Incorrect PIN");
        }

        pinAttemptRecorder.recordSuccess(card.getCardId());
    }

    /** Changes the PIN, which requires proving the current one. */
    @Transactional
    public void changePin(Long userId, String currentPin, String newPin) {
        requireWellFormedPin(newPin);
        // Verifies first, so a wrong current PIN counts against the same attempt limit as
        // any other wrong PIN rather than being a free guessing channel.
        verifyPin(userId, currentPin);

        DebitCard card = requireCardFor(userId);
        card.setPinHash(passwordEncoder.encode(newPin));
        debitCardRepository.save(card);
    }

    /**
     * Blocks the card. Deliberately one-way for the customer: unblocking is a bank operation,
     * performed by the customer's own institution (see unblock).
     */
    @Transactional
    public DebitCard block(Long userId) {
        DebitCard card = requireCardFor(userId);
        if (card.getStatus() == CardStatus.BLOCKED) {
            throw new RuntimeException("This card is already blocked");
        }
        card.setStatus(CardStatus.BLOCKED);
        return debitCardRepository.save(card);
    }

    /**
     * Returns a blocked card to service and clears its wrong-PIN count, so the customer starts
     * again with the full number of attempts.
     *
     * Takes the account rather than a user id: this is the bank's operation, and the caller is
     * responsible for having established that the account belongs to one of its own customers
     * (see InstitutionService).
     */
    @Transactional
    public DebitCard unblock(Account account) {
        DebitCard card = debitCardRepository.findByAccount_AccountId(account.getAccountId())
                .orElseThrow(() -> new RuntimeException("No card has been issued for this account"));
        if (card.getStatus() != CardStatus.BLOCKED) {
            throw new RuntimeException("This card is not blocked");
        }
        card.setStatus(CardStatus.ACTIVE);
        card.setFailedPinAttempts(0);
        return debitCardRepository.save(card);
    }

    private void requireUsable(DebitCard card) {
        switch (card.effectiveStatus()) {
            case BLOCKED -> throw new RuntimeException("This card is blocked");
            case EXPIRED -> throw new RuntimeException("This card has expired");
            case ACTIVE -> { /* usable */ }
        }
    }

    private void requireWellFormedPin(String pin) {
        if (pin == null || pin.length() != PIN_LENGTH || !pin.chars().allMatch(Character::isDigit)) {
            throw new RuntimeException("PIN must be exactly " + PIN_LENGTH + " digits");
        }
    }

    private String generateUniqueCardNumber(Account account) {
        String institutionNumber = accountService.institutionNumberOf(account.getInstitution());
        for (int attempt = 0; attempt < MAX_CARD_NUMBER_ATTEMPTS; attempt++) {
            String candidate = randomCardNumber(institutionNumber);
            if (!debitCardRepository.existsByCardNumber(candidate)) {
                return candidate;
            }
        }
        throw new RuntimeException("Could not allocate a unique card number");
    }

    /**
     * A 16-digit number: a leading 4 (the familiar shape of a card number), then the issuing
     * institution's bank number — where a real card carries its issuer identification — then
     * random digits, and finally the Luhn check digit for everything before it.
     *
     * The prefix takes the place of random digits rather than being added to them, so the
     * number stays 16 digits and Luhn-valid exactly as before.
     */
    private String randomCardNumber(String institutionNumber) {
        StringBuilder digits = new StringBuilder(CARD_NUMBER_LENGTH);
        digits.append(CARD_LEADING_DIGIT).append(institutionNumber);
        while (digits.length() < CARD_NUMBER_LENGTH - 1) {
            digits.append(secureRandom.nextInt(10));
        }
        digits.append(luhnCheckDigit(digits.toString()));
        return digits.toString();
    }

    /**
     * The Luhn check digit for a partial number: double every second digit from the right,
     * subtracting 9 from anything over 9, then choose the digit that brings the total to a
     * multiple of ten.
     */
    private int luhnCheckDigit(String partialNumber) {
        int sum = 0;
        boolean doubling = true; // The check digit will occupy the rightmost place.
        for (int i = partialNumber.length() - 1; i >= 0; i--) {
            int digit = partialNumber.charAt(i) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return (10 - (sum % 10)) % 10;
    }
}
