package org.learning.mldsa.exceptions;

import java.time.Instant;

public record ApiError(Instant timestamp, int status, String message) {
    public ApiError(int status, String message) {
        this(Instant.now(), status, message);
    }
}
