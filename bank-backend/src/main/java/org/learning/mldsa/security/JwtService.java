package org.learning.mldsa.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.learning.mldsa.models.Role;
import org.learning.mldsa.models.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and verifies the JSON Web Tokens that carry a caller's identity between the
 * login endpoint and every subsequent request.
 *
 * The token is signed (HMAC-SHA256), not encrypted — its contents are readable by
 * anyone holding it, and deliberately contain nothing that isn't already known to the
 * account it was issued to. What the signature buys is that the userId and role inside
 * it cannot be altered by the client without invalidating the token.
 */
@Service
public class JwtService {

    private static final String USERNAME_CLAIM = "username";
    private static final String ROLE_CLAIM = "role";

    private final SecretKey signingKey;
    private final Duration tokenLifetime;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiry-minutes}") long expiryMinutes
    ) {
        // Throws if the configured secret is shorter than the 256 bits HS256 requires —
        // failing at startup rather than silently signing with a weak key.
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.tokenLifetime = Duration.ofMinutes(expiryMinutes);
    }

    public String issueToken(User user) {
        Instant issuedAt = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getUserId()))
                .claim(USERNAME_CLAIM, user.getName())
                .claim(ROLE_CLAIM, user.getRole().name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(tokenLifetime)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Verifies the token's signature and expiry, then reconstructs the caller's identity
     * from its claims.
     *
     * parseSignedClaims rejects anything that isn't a well-formed token signed by this
     * exact key — including the "alg: none" trick and tokens signed with a different
     * key — so callers can treat a returned AuthenticatedUser as trustworthy.
     *
     * @throws JwtException if the token is malformed, expired, or not signed by this server
     */
    public AuthenticatedUser parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return new AuthenticatedUser(
                Long.valueOf(claims.getSubject()),
                claims.get(USERNAME_CLAIM, String.class),
                Role.valueOf(claims.get(ROLE_CLAIM, String.class))
        );
    }
}
