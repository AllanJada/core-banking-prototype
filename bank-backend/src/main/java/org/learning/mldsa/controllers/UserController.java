package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.UserRequest;
import org.learning.mldsa.dtos.UserResponse;
import org.learning.mldsa.security.AuthenticatedUser;
import org.learning.mldsa.services.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService userService;

    /**
     * Provisions an account.
     *
     * Restricted to the Bank role, which is what the earlier open-registration note said
     * would happen once that role's console existed. The one exception is an unprovisioned
     * system: while no Bank account exists there is nobody who could authorise the first
     * one, so the very first account may be created anonymously. That window closes
     * permanently the moment a Bank account is created.
     *
     * Enforced in the service rather than by a route rule, because the decision depends on
     * the database's state rather than on the request alone.
     */
    @PostMapping
    ResponseEntity<UserResponse> createNewUser(
            @RequestBody UserRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller
    ) {
        UserResponse createdUser = userService.createNewUser(request, caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
    }

    /**
     * Every account in the system. Restricted to the Bank role because this is an
     * oversight view — the ordinary "who can I send to" case is /counterparties below,
     * which returns only what the caller is actually allowed to transact with.
     */
    @GetMapping
    @PreAuthorize("hasRole('BANK')")
    ResponseEntity<List<UserResponse>> listUsers() {
        return ResponseEntity.ok(userService.listUsers());
    }

    @GetMapping("/counterparties")
    ResponseEntity<List<UserResponse>> listCounterparties(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(userService.listCounterparties(user.userId(), user.role()));
    }

}
