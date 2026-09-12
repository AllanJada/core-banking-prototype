package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.BootstrapStatusResponse;
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

    /** Whether first-time setup is still open. Public, so the sign-in screen can offer it. */
    @GetMapping("/bootstrap")
    ResponseEntity<BootstrapStatusResponse> bootstrapStatus() {
        return ResponseEntity.ok(new BootstrapStatusResponse(userService.isBootstrapOpen()));
    }

    /**
     * Creates the first Central Bank overseer on an empty system, and nothing else.
     *
     * No longer a general provisioning endpoint. Every other account is created by the tier
     * directly above it, through that tier's own endpoint — institutions and overseers under
     * /admin, customers under /institution — each guarded by a class-level @PreAuthorize,
     * rather than one endpoint whose permitted effect depends on a role named in the body.
     *
     * Enforced in the service rather than by a route rule, because whether the window is open
     * depends on the database's state rather than on the request alone.
     */
    @PostMapping
    ResponseEntity<UserResponse> bootstrap(@RequestBody UserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.bootstrap(request));
    }

    /**
     * The institutions the caller may send files to.
     *
     * Institution-only: customers take no part in file transfer, and listing every customer
     * of every bank to any customer would cross the boundary between institutions.
     */
    @GetMapping("/counterparties")
    @PreAuthorize("hasRole('INSTITUTION')")
    ResponseEntity<List<UserResponse>> listCounterparties(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(userService.listCounterparties(user.userId(), user.role()));
    }

}
