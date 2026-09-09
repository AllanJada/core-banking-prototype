package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.PaymentLinkRequest;
import org.learning.mldsa.dtos.PageResponse;
import org.learning.mldsa.dtos.PageRequestParams;
import org.learning.mldsa.dtos.PaymentLinkResponse;
import org.learning.mldsa.dtos.PaymentResponse;
import org.learning.mldsa.models.Payment;
import org.learning.mldsa.models.PaymentLink;
import org.learning.mldsa.security.AuthenticatedUser;
import org.learning.mldsa.services.PaymentLinkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Payment requests — "pay by link".
 *
 * Viewing and paying a link are open to any signed-in customer, since holding the link is
 * what entitles someone to pay it. Creating and cancelling act on the caller's own
 * account, resolved from their token.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payment-links")
@PreAuthorize("hasRole('NORMAL_USER')")
public class PaymentLinkController {

    private final PaymentLinkService paymentLinkService;

    @PostMapping
    ResponseEntity<PaymentLinkResponse> create(
            @RequestBody PaymentLinkRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        PaymentLink link = paymentLinkService.create(
                user.userId(), request.getAmount(), request.getDescription(), request.getExpiresInHours());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(link));
    }

    /** Requests this customer has created, newest first. */
    @GetMapping
    ResponseEntity<PageResponse<PaymentLinkResponse>> myLinks(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ResponseEntity.ok(PageResponse.of(
                paymentLinkService.listForRequester(user.userId(), PageRequestParams.of(page, size)),
                PaymentLinkController::toResponse));
    }

    @GetMapping("/{linkId}")
    ResponseEntity<PaymentLinkResponse> view(@PathVariable String linkId) {
        return ResponseEntity.ok(toResponse(paymentLinkService.require(linkId)));
    }

    @PostMapping("/{linkId}/pay")
    ResponseEntity<PaymentResponse> pay(
            @PathVariable String linkId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Payment payment = paymentLinkService.pay(user.userId(), linkId);
        return ResponseEntity.status(HttpStatus.CREATED).body(new PaymentResponse(
                payment.getPaymentId(),
                payment.getFromAccount().getAccountNumber(),
                payment.getToAccount().getAccountNumber(),
                payment.getAmount(),
                payment.getDescription(),
                payment.getStatus(),
                payment.getFailureReason(),
                payment.getTransactionRef(),
                payment.getCreatedAt()
        ));
    }

    @PostMapping("/{linkId}/cancel")
    ResponseEntity<PaymentLinkResponse> cancel(
            @PathVariable String linkId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ResponseEntity.ok(toResponse(paymentLinkService.cancel(user.userId(), linkId)));
    }

    private static PaymentLinkResponse toResponse(PaymentLink link) {
        return new PaymentLinkResponse(
                link.getLinkId(),
                link.getRequesterAccount().getAccountNumber(),
                link.getAmount(),
                link.getDescription(),
                // Reports the derived status, so a link past its deadline reads as EXPIRED
                // rather than as still payable.
                link.effectiveStatus(),
                link.getExpiresAt(),
                link.getPaidAt(),
                link.getCreatedAt()
        );
    }
}
