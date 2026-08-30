package org.learning.mldsa.services;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.learning.mldsa.models.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and validates the bearer tokens that replace the old "trust whatever userId/senderId
 * the client sends" model (see SecurityConfig and FileTransferController/SlipController for
 * the other halves of that fix). A token's subject is the authenticated user's numeric id;
 * every controller that used to read senderId/userId straight from the request now reads it
 * from the validated token instead, via JwtAuthenticationFilter.
 *
 * The signing key is derived the same way as CryptoService's private-key-at-rest master key
 * (SHA-256 over an arbitrary-length configured secret, so any env var value works regardless
 * of length) — same rationale, kept consistent rather than inventing a second key-derivation
 * approach for a second secret. This is a distinct secret from app.master-key on purpose: a
 * leaked signing key only lets someone forge login sessions, not decrypt stored private keys,
 * and vice versa — no reason to let one leak compromise both.
 */
@Service
public class JwtService {

    // 8 hours: long enough that nobody has to re-log-in mid-session on a demo, short enough
    // that a leaked token doesn't stay usable indefinitely. Tune per real deployment needs.
    private static final long EXPIRATION_MILLIS = 8 * 60 * 60 * 1000L;

    @Value("${app.jwt-secret}")
    private String jwtSecret;

    private SecretKey signingKey() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(jwtSecret.getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public String issueToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getUserId()))
                .claim("username", user.getName())
                .claim("userType", user.getUserType().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(EXPIRATION_MILLIS)))
                .signWith(signingKey())
                .compact();
    }

    /** Throws JwtException (caught by JwtAuthenticationFilter) if the token is missing, expired, or tampered with. */
    public Claims parseClaims(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long extractUserId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }
}
