package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.CustomerResponse;
import org.learning.mldsa.dtos.PageRequestParams;
import org.learning.mldsa.dtos.PageResponse;
import org.learning.mldsa.dtos.ProvisionRequest;
import org.learning.mldsa.security.AuthenticatedUser;
import org.learning.mldsa.services.InstitutionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Function;

/**
 * An institution managing its own customers.
 *
 * Restricted to the INSTITUTION role at the class. The acting institution is always the
 * token's own subject; a customer id in the path is looked up only within that institution
 * (see InstitutionService), so naming another bank's customer finds nothing.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/institution")
@PreAuthorize("hasRole('INSTITUTION')")
public class InstitutionController {

    private final InstitutionService institutionService;

    /** Creates a customer and opens their account at the calling institution. */
    @PostMapping("/customers")
    ResponseEntity<CustomerResponse> createCustomer(
            @RequestBody ProvisionRequest request,
            @AuthenticationPrincipal AuthenticatedUser institution
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(institutionService.createCustomer(institution.userId(), request));
    }

    @GetMapping("/customers")
    ResponseEntity<PageResponse<CustomerResponse>> customers(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal AuthenticatedUser institution
    ) {
        return ResponseEntity.ok(PageResponse.of(
                institutionService.listCustomers(institution.userId(), PageRequestParams.of(page, size)),
                Function.identity()));
    }

    @GetMapping("/customers/{customerId}")
    ResponseEntity<CustomerResponse> customer(
            @PathVariable Long customerId,
            @AuthenticationPrincipal AuthenticatedUser institution
    ) {
        return ResponseEntity.ok(institutionService.requireCustomer(institution.userId(), customerId));
    }

    @PostMapping("/customers/{customerId}/card/unblock")
    ResponseEntity<CustomerResponse> unblockCard(
            @PathVariable Long customerId,
            @AuthenticationPrincipal AuthenticatedUser institution
    ) {
        return ResponseEntity.ok(institutionService.unblockCard(institution.userId(), customerId));
    }
}
