package org.learning.mldsa.configs;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.learning.mldsa.services.JwtService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads "Authorization: Bearer <token>", validates it, and — on success — puts the token's
 * userId in the Spring Security context as the request's principal (a plain Long, not a full
 * UserDetails — this app has no roles/permissions model, just "is this a valid token for this
 * user id", so a custom UserDetailsService would be pure ceremony). Controllers then read the
 * authenticated id via @AuthenticationPrincipal Long instead of trusting a senderId/userId the
 * client put directly in the request — the actual fix for the OWASP API1:2023 (Broken Object
 * Level Authorization) gap described in the earlier "how can this be solved" discussion.
 *
 * A missing or invalid token is not rejected here — it's simply left unauthenticated, and
 * SecurityConfig's authorizeHttpRequests rule is what turns that into a 401 for any endpoint
 * that requires authentication. That split (this filter only authenticates; the security chain
 * decides what requires authentication) is the standard Spring Security division of
 * responsibility for a stateless bearer-token filter.
 */
@RequiredArgsConstructor
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length());
            try {
                Claims claims = jwtService.parseClaims(token);
                Long userId = jwtService.extractUserId(claims);
                var authentication = new UsernamePasswordAuthenticationToken(userId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                // Invalid, expired, or tampered-with token: leave the context unauthenticated
                // rather than throwing from inside the filter — see the class comment for why
                // that decision belongs to the security chain's authorization rule instead.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
