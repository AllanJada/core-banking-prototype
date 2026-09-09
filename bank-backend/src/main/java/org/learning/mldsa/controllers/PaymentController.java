package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.DepositRequest;
import org.learning.mldsa.dtos.PageResponse;
import org.learning.mldsa.dtos.PageRequestParams;
import org.learning.mldsa.dtos.DepositResponse;
import org.learning.mldsa.dtos.PaymentRequest;
import org.learning.mldsa.dtos.PaymentResponse;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.Deposit;
import org.learning.mldsa.models.Payment;
import org.learning.mldsa.security.AuthenticatedUser;
import org.learning.mldsa.services.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Money operations for a customer's own account.
 *
 * As with the account views, the acting account comes from the token rather than the
 * request body — there is no field here that would let a caller spend from an account
 * they do not hold.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payments")
@PreAuthorize("hasRole('NORMAL_USER')")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/deposits")
    ResponseEntity<DepositResponse> deposit(
            @RequestBody DepositRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Deposit deposit = paymentService.deposit(user.userId(), request.getAmount(), request.getDescription());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(deposit));
    }

    @PostMapping
    ResponseEntity<PaymentResponse> pay(
            @RequestBody PaymentRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Payment payment = paymentService.pay(
                user.userId(), request.getToAccountNumber(), request.getAmount(), request.getDescription());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(payment));
    }

    /** This account's outgoing payments, including refused ones and why they were refused. */
    @GetMapping
    ResponseEntity<PageResponse<PaymentResponse>> myPayments(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return ResponseEntity.ok(PageResponse.of(
                paymentService.listPaymentsFor(user.userId(), PageRequestParams.of(page, size)),
                PaymentController::toResponse));
    }

    private static PaymentResponse toResponse(Payment payment) {
        Account to = payment.getToAccount();
        return new PaymentResponse(
                payment.getPaymentId(),
                payment.getFromAccount().getAccountNumber(),
                to == null ? null : to.getAccountNumber(),
                payment.getAmount(),
                payment.getDescription(),
                payment.getStatus(),
                payment.getFailureReason(),
                payment.getTransactionRef(),
                payment.getCreatedAt()
        );
    }

    private static DepositResponse toResponse(Deposit deposit) {
        return new DepositResponse(
                deposit.getDepositId(),
                deposit.getAccount().getAccountNumber(),
                deposit.getAmount(),
                deposit.getDescription(),
                deposit.getTransactionRef(),
                deposit.getDepositedAt()
        );
    }
}
