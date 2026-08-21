package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.UserRequest;
import org.learning.mldsa.dtos.UserResponse;
import org.learning.mldsa.services.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService userService;

    @PostMapping
    ResponseEntity<UserResponse> createNewUser(@RequestBody UserRequest request) {
        UserResponse createdUser = userService.createNewUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
    }

}
