package org.learning.mldsa.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * A @PreAuthorize check that fails throws AccessDeniedException from inside the
     * controller call, so it lands here rather than at the access-denied handler wired
     * into the security filter chain (that one only sees denials raised by the chain's
     * own route rules). Without this handler the catch-all below would answer a
     * permissions failure with "400 Access Denied", making a role problem look like a
     * malformed request. It must stay declared separately from RuntimeException — Spring
     * dispatches to the most specific handler, which is what gets this a 403.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        ApiError error = new ApiError(HttpStatus.FORBIDDEN.value(),
                "Your account's role is not permitted to perform this action");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    // Every service in this app throws plain RuntimeException for expected failures
    // (not found, validation, etc.) — surfaced here as 400s with the actual message.
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> handleRuntimeException(RuntimeException ex) {
        ApiError error = new ApiError(HttpStatus.BAD_REQUEST.value(), ex.getMessage());
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        ApiError error = new ApiError(HttpStatus.CONTENT_TOO_LARGE.value(), "Uploaded file is too large");
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(error);
    }
}
