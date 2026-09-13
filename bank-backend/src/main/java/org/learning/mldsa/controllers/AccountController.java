package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.AccountResponse;
import org.learning.mldsa.dtos.PageResponse;
import org.learning.mldsa.dtos.PageRequestParams;
import org.learning.mldsa.dtos.PostingResponse;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.Posting;
import org.learning.mldsa.models.User;
import org.learning.mldsa.security.AuthenticatedUser;
import org.learning.mldsa.services.AccountService;
import org.learning.mldsa.services.StatementService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * A customer's view of their own account and its history.
 *
 * Every route resolves the account from the caller's token rather than taking an account
 * number or id, so there is no parameter to change in order to read someone else's
 * balance. The Bank role's cross-account view is a separate concern and belongs to the
 * admin console, not here.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/accounts")
@PreAuthorize("hasRole('NORMAL_USER')")
public class AccountController {

    private final AccountService accountService;
    private final StatementService statementService;

    @GetMapping("/me")
    ResponseEntity<AccountResponse> myAccount(@AuthenticationPrincipal AuthenticatedUser user) {
        Account account = accountService.requireAccountFor(user.userId());
        User institution = account.getInstitution();
        return ResponseEntity.ok(new AccountResponse(
                account.getAccountNumber(),
                account.getCurrency(),
                accountService.balanceOf(account.getAccountId()),
                account.getOpenedAt(),
                institution.getName(),
                institution.getInstitutionCode()
        ));
    }

    @GetMapping("/me/postings")
    ResponseEntity<PageResponse<PostingResponse>> myPostings(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Account account = accountService.requireAccountFor(user.userId());
        return ResponseEntity.ok(PageResponse.of(
                accountService.listPostings(account.getAccountId(), PageRequestParams.of(page, size)),
                AccountController::toResponse));
    }

    /**
     * The account's statement for a date range, as a PDF.
     *
     * Dates are whole days in UTC, and both ends are inclusive as a reader would expect —
     * a statement "to the 30th" includes everything that happened on the 30th.
     */
    @GetMapping("/me/statement")
    ResponseEntity<byte[]> statement(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        byte[] pdf = statementService.generateStatementPdf(user.userId(), from, to);
        String filename = "statement-" + from + "-to-" + to + ".pdf";

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(pdf);
    }

    private static PostingResponse toResponse(Posting posting) {
        return new PostingResponse(
                posting.getPostingId(),
                posting.getDirection(),
                posting.getAmount(),
                posting.getDescription(),
                posting.getTransactionRef(),
                posting.getPostedAt()
        );
    }
}
