package org.learning.mldsa.models;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A debit card linked to an account.
 *
 * The card number is a separate 16-digit identifier from the account number it draws on,
 * matching how real cards work — the two are not interchangeable and knowing one does not
 * give you the other.
 *
 * Two things are deliberately absent. There is no CVV: PCI DSS forbids retaining the card
 * verification value once a transaction has been authorised, and since this system verifies
 * cardholders by PIN there is nothing a stored CVV would buy that would justify holding it.
 * And there is no plaintext PIN — only a bcrypt hash, the same treatment account passwords
 * get, so the PIN cannot be read back out of the database by anyone, including this system.
 */
@Entity
@Table(name = "debit_cards", schema = "cards")
@Data
public class DebitCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "card_id")
    private Long cardId;

    // Luhn-valid, like a real card number, so an obvious typo fails a checksum rather than
    // silently addressing a card that happens to exist.
    @Column(name = "card_number", nullable = false, unique = true, updatable = false, length = 16)
    private String cardNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    /** The last day the card works — cards expire at the end of their month. */
    @Column(name = "expires_on", nullable = false, updatable = false)
    private LocalDate expiresOn;

    // Only ever ACTIVE or BLOCKED here; see CardStatus for why EXPIRED is derived.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CardStatus status;

    /** bcrypt hash of the PIN. Never the PIN itself, and never returned by any endpoint. */
    @Column(name = "pin_hash", nullable = false)
    private String pinHash;

    // Consecutive wrong PINs. Reset on a correct one, and the card is blocked when it hits
    // the limit — the same protection a real card has against someone simply trying all
    // 10,000 possibilities.
    @Column(name = "failed_pin_attempts", nullable = false)
    private int failedPinAttempts;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    /**
     * The status as it stands now, accounting for the expiry date.
     *
     * A blocked card stays blocked rather than being reported as expired: how it became
     * unusable matters, and blocking is the more specific fact.
     */
    public CardStatus effectiveStatus() {
        if (status == CardStatus.ACTIVE && LocalDate.now().isAfter(expiresOn)) {
            return CardStatus.EXPIRED;
        }
        return status;
    }

    /** The card number as it is safe to display: last four digits only. */
    public String maskedNumber() {
        return "**** **** **** " + cardNumber.substring(cardNumber.length() - 4);
    }
}
