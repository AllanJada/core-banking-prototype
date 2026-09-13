package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.models.CardStatus;
import org.learning.mldsa.models.DebitCard;
import org.learning.mldsa.repositories.DebitCardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the count of consecutive wrong PINs, and blocks the card when it runs out.
 *
 * This is a separate bean with REQUIRES_NEW for the same reason failed payments are: a
 * wrong PIN is reported by throwing, and that rolls back the transaction the check ran in
 * — which would take the incremented counter with it. The count would then reset on every
 * attempt and the card would never lock, leaving all 10,000 PINs open to being tried in
 * turn. Committing the attempt in its own transaction is what makes the limit real.
 *
 * It must be a separate bean rather than another method here: Spring applies propagation
 * through a proxy, and a service calling its own method bypasses it silently.
 */
@RequiredArgsConstructor
@Service
public class CardPinAttemptRecorder {

    /** Wrong PINs tolerated before the card locks — the usual three. */
    static final int MAX_FAILED_PIN_ATTEMPTS = 3;

    private final DebitCardRepository debitCardRepository;

    /**
     * Counts one wrong attempt, blocking the card if that was the last one allowed.
     *
     * @return true if the card is now blocked
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(Long cardId) {
        DebitCard card = debitCardRepository.findById(cardId)
                .orElseThrow(() -> new RuntimeException("Card not found"));

        card.setFailedPinAttempts(card.getFailedPinAttempts() + 1);
        boolean nowBlocked = card.getFailedPinAttempts() >= MAX_FAILED_PIN_ATTEMPTS;
        if (nowBlocked) {
            card.setStatus(CardStatus.BLOCKED);
        }
        debitCardRepository.save(card);

        return nowBlocked;
    }

    /**
     * Clears the count after a correct PIN.
     *
     * Also in its own transaction, so the reset does not depend on whatever operation the
     * PIN was checked for going on to succeed — proving the PIN happened either way.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long cardId) {
        debitCardRepository.findById(cardId).ifPresent(card -> {
            if (card.getFailedPinAttempts() != 0) {
                card.setFailedPinAttempts(0);
                debitCardRepository.save(card);
            }
        });
    }
}
