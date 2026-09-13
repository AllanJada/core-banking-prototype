package org.learning.mldsa.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.learning.mldsa.exceptions.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Turns Spring Security's two rejection cases into the same {timestamp, status, message}
 * JSON body that GlobalExceptionHandler already returns for everything else.
 *
 * Without this, an unauthenticated call would return a framework-default HTML error page,
 * which the frontend's error unwrapping (see client.ts) can't read — it would surface as
 * a generic "Request failed (401)" instead of a usable message. The two cases are kept
 * distinct on purpose: 401 means "you have not proven who you are", 403 means "you have,
 * and this role isn't allowed here" — collapsing them would make a permissions bug look
 * identical to an expired session.
 */
@RequiredArgsConstructor
@Component
public class RestAuthenticationErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        writeError(response, HttpStatus.UNAUTHORIZED, "Authentication required — sign in and retry");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        writeError(response, HttpStatus.FORBIDDEN, "Your account's role is not permitted to perform this action");
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Writing straight to the servlet writer bypasses Spring's message converters, so
        // the charset has to be set here — its default is ISO-8859-1, which turns any
        // non-ASCII character in a message into "?".
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), new ApiError(status.value(), message));
    }
}
