package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.CustomerResponse;
import org.learning.mldsa.dtos.DepositRequest;
import org.learning.mldsa.dtos.DepositResponse;
import org.learning.mldsa.dtos.InstitutionPaymentResponse;
import org.learning.mldsa.dtos.InstitutionSummaryResponse;
import org.learning.mldsa.dtos.PageRequestParams;
import org.learning.mldsa.dtos.PageResponse;
import org.learning.mldsa.dtos.ProvisionRequest;
import org.learning.mldsa.dtos.SettlementMessageResponse;
import org.learning.mldsa.models.SettlementMessage;
import org.learning.mldsa.security.AuthenticatedUser;
import org.learning.mldsa.services.InstitutionService;
import org.learning.mldsa.services.SettlementMessageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    private final SettlementMessageService settlementMessageService;

    /** Creates a customer and opens their account at the calling institution. */
    @PostMapping("/customers")
    ResponseEntity<CustomerResponse> createCustomer(
            @RequestBody ProvisionRequest request,
            @AuthenticationPrincipal AuthenticatedUser institution
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(institutionService.createCustomer(institution.userId(), request));
    }

    /** This institution's own aggregates, settlement position and headroom under its cap. */
    @GetMapping("/summary")
    ResponseEntity<InstitutionSummaryResponse> summary(
            @AuthenticationPrincipal AuthenticatedUser institution
    ) {
        return ResponseEntity.ok(institutionService.summary(institution.userId()));
    }

    /** Payments by this institution's own customers, with the reasons refusals happened. */
    @GetMapping("/payments")
    ResponseEntity<PageResponse<InstitutionPaymentResponse>> payments(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal AuthenticatedUser institution
    ) {
        return ResponseEntity.ok(PageResponse.of(
                institutionService.listPayments(institution.userId(), PageRequestParams.of(page, size)),
                Function.identity()));
    }

    /**
     * The ISO 20022 interbank messages this institution is a party to, sent and received.
     *
     * Both directions, and only those two: a message between two other banks is not this
     * institution's to read.
     */
    @GetMapping("/settlement-messages")
    ResponseEntity<PageResponse<SettlementMessageResponse>> settlementMessages(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal AuthenticatedUser institution
    ) {
        return ResponseEntity.ok(PageResponse.of(
                settlementMessageService.listForInstitution(institution.userId(), PageRequestParams.of(page, size)),
                Function.identity()));
    }

    /**
     * The pacs.008 itself, re-verified before it is served: its stored bytes must still hash
     * to what was signed, and that signature must still verify against the sending bank's key.
     */
    @GetMapping("/settlement-messages/{messageId}/xml")
    ResponseEntity<byte[]> settlementMessageXml(
            @PathVariable Long messageId,
            @AuthenticationPrincipal AuthenticatedUser institution
    ) {
        SettlementMessage message = settlementMessageService.requireForInstitution(messageId, institution.userId());
        return xmlResponse(message, settlementMessageService.loadVerified(message));
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

    /**
     * Takes a deposit for one of this institution's customers — the counter operation.
     *
     * The customer is addressed by id in the path, but only ever found within the signed-in
     * institution, so this cannot credit another bank's customer. The institution funding the
     * deposit is the one holding the token; there is no field here naming a different one.
     */
    @PostMapping("/customers/{customerId}/deposits")
    ResponseEntity<DepositResponse> deposit(
            @PathVariable Long customerId,
            @RequestBody DepositRequest request,
            @AuthenticationPrincipal AuthenticatedUser institution
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(institutionService.depositForCustomer(
                institution.userId(), customerId, request.getAmount(), request.getDescription()));
    }

    /** Served as a file named after the reference it carries, which is also the ledger's. */
    static ResponseEntity<byte[]> xmlResponse(SettlementMessage message, byte[] xml) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + message.getMessageType() + "-" + message.getUetr() + ".xml\"")
                .body(xml);
    }
}
