package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.LoginRequest;
import org.learning.mldsa.dtos.LoginResponse;
import org.learning.mldsa.dtos.UserResponse;
import org.learning.mldsa.security.AuthenticatedUser;
import org.learning.mldsa.services.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Who the current token says you are. The frontend calls this on startup to find out
     * whether a token it restored from storage is still valid, rather than discovering it
     * expired partway through rendering a dashboard.
     */
    @GetMapping("/me")
    ResponseEntity<UserResponse> currentUser(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(new UserResponse(user.userId(), user.username(), user.role()));
    }
}
