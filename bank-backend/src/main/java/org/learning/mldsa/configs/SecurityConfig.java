package org.learning.mldsa.configs;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.security.IdempotencyFilter;
import org.learning.mldsa.security.JwtAuthenticationFilter;
import org.learning.mldsa.security.RestAuthenticationErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@RequiredArgsConstructor
@Configuration
// Enables the @PreAuthorize checks that each controller uses to declare which roles may
// call it. Route-level rules below are the coarse net; @PreAuthorize is where the actual
// per-endpoint role policy lives, next to the code it protects.
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final IdempotencyFilter idempotencyFilter;
    private final RestAuthenticationErrorHandler authenticationErrorHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // No server-side session: identity comes from the JWT on each request, so
                // there is nothing to keep between them.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Anonymous by necessity — you cannot hold a token before logging in.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        // Bootstrap: reachable without a token, but it creates only the first
                        // Central Bank overseer, and only while none exists. UserService makes
                        // that decision because it depends on the database's state, not on the
                        // request. Once an overseer exists this route refuses everyone.
                        .requestMatchers(HttpMethod.POST, "/api/v1/users").permitAll()
                        // Lets the sign-in screen know whether to offer first-time setup.
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/bootstrap").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationErrorHandler)
                        .accessDeniedHandler(authenticationErrorHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // After authorization, not before it: a key is claimed per caller, so there
                // has to be a caller, and a request that is about to be refused for its role
                // should be refused rather than handed a claim on a key it never used.
                .addFilterAfter(idempotencyFilter, AuthorizationFilter.class);
        return http.build();
    }

    // Demo scope: allows the Vite dev server to call this API. Tighten this list
    // (and drop the wildcard header allowance) before this touches anything real.
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
