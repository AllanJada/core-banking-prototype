package org.learning.mldsa.configs;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Was: authorizeHttpRequests(auth -> auth.anyRequest().permitAll()) plus a wildcard CORS
 * origin — every endpoint (list every user, send a file as anyone, read anyone's inbox) was
 * reachable by any caller from any website, no authentication at all. That's the "HTTP layer
 * wide open" gap from the earlier "how can this be solved" discussion; this is the fix.
 *
 * Now: only login and registration are public. Everything else requires a valid bearer token
 * (checked by JwtAuthenticationFilter, registered below, ahead of Spring Security's own
 * authorization check), and CORS is scoped to the frontend's actual origins instead of "*".
 */
@RequiredArgsConstructor
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Stateless bearer-token API — no server-side session, no JSESSIONID cookie a
                // browser would auto-attach to a forged cross-site request, which is the
                // exact thing CSRF protection exists to stop. Disabling it here is a
                // considered choice for this auth model, not an oversight: Spring Security's
                // own documentation names "an API used exclusively by non-browser clients, or
                // one that uses a token to authenticate" as the case where disabling CSRF is
                // correct (docs.spring.io, Spring Security CSRF guide) — every request here
                // now needs an Authorization header a browser never attaches on its own, so
                // there is nothing left for a forged cross-site request to ride on.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        // Login and registration are the two things a client without a token
                        // yet must be able to reach. Everything else — including GET
                        // /api/v1/users, previously wide open — now requires one.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/users").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    // Matches the {status, message} shape GlobalExceptionHandler already uses
                    // for every other error, so the frontend's existing error handling (which
                    // reads body.message) shows something meaningful instead of a bare 401.
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"status\":401,\"message\":\"Authentication required\"}");
                }))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    // Narrowed from the previous wildcard ("*") to the concrete origins the Vite dev server
    // actually uses (5173 default `npm run dev`, 4173 `npm run preview`) — a wildcard CORS
    // origin meant any website's JavaScript could call this API using a signed-in user's own
    // browser session. Add the real deployed frontend origin here before this serves anything
    // beyond local dev, and drop the dev origins at that point too.
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:4173"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // "Authorization" replaces the previous wildcard header allowance — every request now
        // needs it to carry the bearer token; Content-Type covers the JSON/multipart bodies.
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
