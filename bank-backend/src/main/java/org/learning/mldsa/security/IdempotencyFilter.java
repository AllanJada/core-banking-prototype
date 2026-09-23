package org.learning.mldsa.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.learning.mldsa.exceptions.ApiError;
import org.learning.mldsa.models.IdempotencyRecord;
import org.learning.mldsa.services.IdempotencyService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Makes a retried request safe to send twice.
 *
 * A client whose request times out cannot tell whether the payment happened. Retrying is the
 * only thing it can do, and without this that retry pays again — a second set of postings,
 * and across banks a second pacs.008. The worst case is a deposit, where a second submission
 * does not merely move money twice but creates money that was never handed over.
 *
 * The caller sends an {@code Idempotency-Key}; the first request through claims it and its
 * response is stored against it, and any later request carrying the same key is answered from
 * that store rather than executed. The header is optional — a request without one behaves
 * exactly as it did before — so this protects clients that ask for it without breaking those
 * that do not.
 *
 * <p><b>Why a filter rather than a check in each controller.</b> The guarantee has to cover
 * the whole request, including the part where the response is produced, and it has to behave
 * identically on all of the endpoints it guards. Four copies of that would be four chances for
 * one of them to differ.
 *
 * <p><b>Why it runs where it does.</b> After authentication and authorization, because a key
 * is claimed per caller and there is no caller to scope it to until the token has been read —
 * and because an unauthorised request should be refused, not handed a claim on a key.
 */
@RequiredArgsConstructor
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";

    /**
     * The endpoints where a repeat costs money.
     *
     * Deliberately a list rather than "every POST": issuing a card twice matters, but blocking
     * a card twice is harmless and composing a slip twice produces two documents that are
     * meant to be distinguishable. A file upload is excluded too — hashing a 50MB body to
     * fingerprint it would cost more than the duplicate it prevents, and approving a transfer
     * twice is already refused by the unique payment_id constraint added in V5.
     */
    private static final List<Pattern> GUARDED = List.of(
            Pattern.compile("^/api/v1/payments$"),
            Pattern.compile("^/api/v1/institution/customers/\\d+/deposits$"),
            Pattern.compile("^/api/v1/payment-links/[^/]+/pay$"),
            Pattern.compile("^/api/v1/cards$")
    );

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return GUARDED.stream().noneMatch(pattern -> pattern.matcher(path).matches());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String key = IdempotencyService.normaliseKey(request.getHeader(HEADER));
        Long callerId = currentCallerId();

        // No key, or nobody to scope it to: the request runs exactly as it did before this
        // existed. Requiring the header would break every client that has not adopted it yet,
        // which is a decision for the API's owners rather than a side effect of adding this.
        if (key == null || key.isEmpty() || callerId == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!IdempotencyService.isWellFormed(key)) {
            writeError(response, HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 8 to 120 printable characters");
            return;
        }

        // Read once, up front: the body is needed now to fingerprint the request, and again
        // later by the controller. A wrapper hands the same bytes to both.
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        String hash = idempotencyService.hashBody(body);
        HttpServletRequest replayable = new CachedBodyRequest(request, body);

        IdempotencyRecord claim;
        try {
            claim = idempotencyService.claim(callerId, key, request.getMethod(),
                    request.getRequestURI(), hash);
        } catch (DataIntegrityViolationException alreadyClaimed) {
            // Somebody got there first — either this same client retrying, or its own
            // duplicate arriving concurrently. Which of those it is decides the answer.
            answerFromExistingClaim(callerId, key, hash, response);
            return;
        }

        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        boolean completed = false;
        try {
            filterChain.doFilter(replayable, captured);

            int status = captured.getStatus();
            if (IdempotencyService.isSuccess(status)) {
                idempotencyService.complete(claim.getIdempotencyId(), status,
                        new String(captured.getContentAsByteArray(), StandardCharsets.UTF_8));
            } else {
                // A refusal moved no money, so the client may legitimately send the same
                // request again once the reason for it is gone. Keeping the key would turn a
                // temporary refusal into a permanent one.
                idempotencyService.release(claim.getIdempotencyId());
            }
            completed = true;
        } finally {
            if (!completed) {
                // The request threw before a status was decided. Nothing was answered, so the
                // key must not stay claimed — otherwise the one thing the client can safely
                // do, retry, would be refused forever.
                idempotencyService.release(claim.getIdempotencyId());
            }
            captured.copyBodyToResponse();
        }
    }

    /**
     * Answers a request whose key was already taken.
     *
     * Three cases, and they mean different things. A different body under the same key is a
     * client bug, and replaying the first response would confirm a payment that was never
     * made — so it is refused outright. A claim with no response yet is a duplicate that
     * arrived while the original is still running; there is no answer to give, and the honest
     * reply is that it is in progress. Otherwise the stored response is returned verbatim.
     */
    private void answerFromExistingClaim(Long callerId, String key, String hash,
                                         HttpServletResponse response) throws IOException {
        Optional<IdempotencyRecord> found = idempotencyService.findExisting(callerId, key);
        if (found.isEmpty()) {
            // Claimed and then released between the insert failing and this read — the
            // request it guarded was refused. Treat the key as free to use again.
            writeError(response, HttpStatus.CONFLICT,
                    "That Idempotency-Key was just released by a failed request — retry it");
            return;
        }

        IdempotencyRecord record = found.get();
        if (!record.getRequestHash().equals(hash)) {
            writeError(response, HttpStatus.UNPROCESSABLE_ENTITY,
                    "This Idempotency-Key was already used for a different request");
            return;
        }
        if (!record.isComplete()) {
            writeError(response, HttpStatus.CONFLICT,
                    "A request with this Idempotency-Key is still in progress");
            return;
        }

        response.setStatus(record.getResponseStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Says plainly that nothing ran this time, which is the difference between this and
        // the original response that is otherwise byte for byte identical.
        response.setHeader("Idempotent-Replay", "true");
        if (record.getResponseBody() != null) {
            response.getWriter().write(record.getResponseBody());
        }
    }

    private static Long currentCallerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user.userId();
        }
        return null;
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                new ApiError(Instant.now(), status.value(), message)));
    }

    /**
     * Serves a request body that has already been read.
     *
     * The filter has to read the body to fingerprint it, and a servlet input stream can only
     * be read once — so without this the controller would find an empty body.
     */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("Asynchronous reads are not used here");
                }

                @Override
                public int read() {
                    return source.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
