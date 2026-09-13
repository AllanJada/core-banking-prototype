package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.CardPinRequest;
import org.learning.mldsa.dtos.CardResponse;
import org.learning.mldsa.models.DebitCard;
import org.learning.mldsa.security.AuthenticatedUser;
import org.learning.mldsa.services.CardService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's debit card.
 *
 * The card always belongs to the caller's own account, resolved from their token — there is
 * no card identifier in any path here, so one customer cannot address another's card.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/cards")
@PreAuthorize("hasRole('NORMAL_USER')")
public class CardController {

    private final CardService cardService;

    /**
     * Issues a card. This is the only response that carries the full card number — every
     * later read is masked, so it needs to be captured now.
     */
    @PostMapping
    ResponseEntity<CardResponse> issue(
            @RequestBody CardPinRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        DebitCard card = cardService.issue(user.userId(), request.getPin());
        return ResponseEntity.status(HttpStatus.CREATED).body(new CardResponse(
                card.getCardNumber(),
                card.getAccount().getAccountNumber(),
                card.getExpiresOn(),
                card.effectiveStatus()
        ));
    }

    /** The card's details, masked. Returns 200 with no body when no card has been issued. */
    @GetMapping("/me")
    ResponseEntity<CardResponse> myCard(@AuthenticationPrincipal AuthenticatedUser user) {
        return cardService.findCardFor(user.userId())
                .map(card -> ResponseEntity.ok(toMaskedResponse(card)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Confirms the cardholder's PIN. Wrong attempts count towards blocking the card. */
    @PostMapping("/me/verify-pin")
    ResponseEntity<Void> verifyPin(
            @RequestBody CardPinRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        cardService.verifyPin(user.userId(), request.getPin());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/change-pin")
    ResponseEntity<Void> changePin(
            @RequestBody CardPinRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        cardService.changePin(user.userId(), request.getCurrentPin(), request.getPin());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/block")
    ResponseEntity<CardResponse> block(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(toMaskedResponse(cardService.block(user.userId())));
    }

    private static CardResponse toMaskedResponse(DebitCard card) {
        return new CardResponse(
                card.maskedNumber(),
                card.getAccount().getAccountNumber(),
                card.getExpiresOn(),
                card.effectiveStatus()
        );
    }
}
