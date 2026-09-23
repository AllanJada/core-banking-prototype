package org.learning.mldsa.models;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * One caller's claim on one idempotency key, and the response the request under it produced.
 *
 * The row is written before the request runs and completed after it, so its lifetime spans
 * the work it guards. A row with no response is a request still in flight — which is what a
 * concurrent duplicate finds, as distinct from a later retry, which finds the answer.
 *
 * Uniqueness of (caller, key) is enforced by an index rather than by looking first: two
 * identical requests arriving together would otherwise both find the key unclaimed.
 */
@Entity
@Table(name = "idempotency_keys", schema = "payments")
@Data
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "idempotency_id")
    private Long idempotencyId;

    /** Scoped per caller, so one caller cannot replay another's response by guessing a key. */
    @Column(name = "caller_id", nullable = false, updatable = false)
    private Long callerId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "request_method", nullable = false, updatable = false, length = 8)
    private String requestMethod;

    @Column(name = "request_path", nullable = false, updatable = false, length = 255)
    private String requestPath;

    /** SHA-256 of the request body, so a key reused with different content is detectable. */
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    /** Null while the request is still running. */
    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Whether the guarded request finished and left an answer to replay. */
    public boolean isComplete() {
        return responseStatus != null;
    }
}
