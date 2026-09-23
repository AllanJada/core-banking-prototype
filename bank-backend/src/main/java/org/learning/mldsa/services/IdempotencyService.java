package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.models.IdempotencyRecord;
import org.learning.mldsa.repositories.IdempotencyRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Claiming and completing idempotency keys.
 *
 * Every method here runs in its own transaction, and that is the entire point rather than a
 * detail. The claim has to be committed and visible <em>before</em> the request it guards
 * starts work, or a duplicate arriving a millisecond later would find the key unclaimed and
 * run the payment a second time. Joining the caller's transaction would make the claim
 * invisible until the payment had already been made, which is exactly too late — and would
 * also roll the claim back whenever the request failed, releasing a key that had been used.
 *
 * The same reasoning the failed-payment recorder and the card PIN counter already follow:
 * when a record has to outlive, or precede, the transaction it describes, it needs a
 * transaction of its own.
 */
@RequiredArgsConstructor
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    /**
     * Takes the key for this caller, or fails because somebody already has it.
     *
     * The insert is flushed deliberately: without it the unique-index violation would not
     * surface until the transaction committed, which is after the caller has decided it holds
     * the claim and gone ahead. The exception is left to propagate — the caller distinguishes
     * "already claimed" from "claimed by me" by which of those two happens, not by looking
     * first, because looking first is the race this is here to close.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord claim(Long callerId, String key, String method, String path, String bodyHash) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setCallerId(callerId);
        record.setIdempotencyKey(key);
        record.setRequestMethod(method);
        record.setRequestPath(path);
        record.setRequestHash(bodyHash);
        record.setCreatedAt(Instant.now());
        return idempotencyRecordRepository.saveAndFlush(record);
    }

    /** The existing claim on a key, read in its own transaction so it sees committed work. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> findExisting(Long callerId, String key) {
        return idempotencyRecordRepository.findByCallerIdAndIdempotencyKey(callerId, key);
    }

    /**
     * Stores what the guarded request answered, so a retry is given the same answer rather
     * than being executed again.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long idempotencyId, int status, String body) {
        idempotencyRecordRepository.findById(idempotencyId).ifPresent(record -> {
            record.setResponseStatus(status);
            record.setResponseBody(body);
            record.setCompletedAt(Instant.now());
            idempotencyRecordRepository.save(record);
        });
    }

    /**
     * Gives the key back after a request that did not succeed.
     *
     * A refusal — insufficient funds, a limit, a customer who is not this bank's — moved no
     * money, so the client is entitled to try the same request again once the reason is gone.
     * Holding the key would turn a temporary refusal into a permanent one for that key, and
     * a client that generates one key per intent would have no way to retry its own payment.
     *
     * Successes are never released: repeating one is the thing this exists to prevent.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long idempotencyId) {
        idempotencyRecordRepository.deleteById(idempotencyId);
    }

    /** SHA-256 of a request body, as lowercase hex. Empty and absent bodies hash alike. */
    public String hashBody(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body == null ? new byte[0] : body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Whether a stored response should be replayed rather than the request re-run. */
    public static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    public static String normaliseKey(String raw) {
        return raw == null ? null : raw.trim();
    }

    public static boolean isWellFormed(String key) {
        // Long enough to be a real identifier rather than a counter two clients would both
        // pick, and short enough to fit the column. A UUID sits comfortably inside this.
        return key != null && key.length() >= 8 && key.length() <= 120
                && key.chars().allMatch(c -> c > 0x20 && c < 0x7F);
    }
}
