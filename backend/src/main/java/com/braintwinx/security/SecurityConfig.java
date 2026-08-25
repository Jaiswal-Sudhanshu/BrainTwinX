package com.braintwinx.security;

import com.braintwinx.config.CorrelationIdFilter;
import com.braintwinx.exception.ApiError;
import com.braintwinx.exception.ApiErrorCode;
import tools.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Central security configuration (project brief section 7).
 *
 * <p><strong>Deny by default.</strong> The chain ends with {@code anyRequest().denyAll()}, not
 * {@code authenticated()}. The difference matters: with {@code authenticated()}, a new endpoint
 * added later is reachable by <em>any</em> logged-in user, whichever role they hold. With
 * {@code denyAll()}, a new endpoint is unreachable until someone grants access to it explicitly.
 * A forgotten rule then fails closed and shows up immediately in testing, instead of silently
 * exposing a resource — the sequence that produces most real-world access-control defects.
 *
 * <p>Only three paths are public, and each is justified below. Everything else, including paths
 * that do not exist yet, is denied.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
@EnableMethodSecurity            // enables @PreAuthorize for per-endpoint rules
public class SecurityConfig {

    /**
     * BCrypt strength.
     *
     * <p>Raised above the Spring default of 10. Cost 12 is roughly four times slower to verify,
     * which is negligible for an interactive login and materially raises the cost of an offline
     * attack against a stolen hash.
     */
    private static final int BCRYPT_STRENGTH = 12;

    private final ObjectMapper objectMapper;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final String allowedOrigins;

    public SecurityConfig(ObjectMapper objectMapper,
                          JwtAuthenticationFilter jwtAuthenticationFilter,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${braintwinx.cors.allowed-origins:}") String allowedOrigins) {
        this.objectMapper = objectMapper;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Stateless JWT API: no session to fixate, and no CSRF token to carry.
                // CSRF protection is unnecessary precisely BECAUSE no cookie carries the
                // credential — the browser never attaches the bearer token automatically.
                // If a cookie-based flow is ever introduced, CSRF must be re-enabled.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())   // logout is an API endpoint, not a form post

                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000))
                        // An API returns no HTML, so nothing legitimate needs to execute.
                        .contentSecurityPolicy(csp ->
                                csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers
                                        .ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                )

                .authorizeHttpRequests(auth -> auth
                        // --- Public: authentication entry points ---
                        // Necessarily unauthenticated. Rate limiting and lockout, not
                        // authorisation, are what protect these (Phase 14).
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh").permitAll()

                        // --- Public: liveness and readiness ---
                        // Required by orchestrators before a token could be obtained.
                        // Detail is suppressed via management.endpoint.health.show-details.
                        .requestMatchers(HttpMethod.GET,
                                "/actuator/health",
                                "/actuator/health/**").permitAll()

                        // --- Preflight ---
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // --- Internal AI endpoints are never served by this application ---
                        // Explicitly denied so a future mapping under this prefix cannot become
                        // publicly reachable by accident (brief section 17).
                        .requestMatchers("/internal/**").denyAll()

                        .requestMatchers("/api/v1/auth/logout").authenticated()

                        // --- Patients ---
                        // Authentication only at this layer. Role is gated by @PreAuthorize on
                        // the controller, and the access SCOPE (which patients a caller may see)
                        // is enforced in PatientService — deliberately not here, so it applies
                        // however the operation is reached rather than only via HTTP.
                        .requestMatchers("/api/v1/patients", "/api/v1/patients/**").authenticated()

                        // --- Everything else, including not-yet-existing paths ---
                        .anyRequest().denyAll()
                )

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

                .exceptionHandling(ex -> ex
                        // Both handlers emit the standard envelope. Without them Spring returns
                        // its own HTML error page, which breaks the API contract and can
                        // disclose more than intended.
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, ApiErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, deniedException) ->
                                writeError(response, ApiErrorCode.FORBIDDEN))
                );

        return http.build();
    }

    /**
     * CORS restricted to an explicit origin list.
     *
     * <p>Never a wildcard: {@code allowCredentials(true)} with {@code *} is rejected by browsers,
     * and permitting arbitrary origins against an authenticated medical API would be indefensible
     * regardless.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        configuration.setAllowedOrigins(origins);

        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                CorrelationIdFilter.TRACE_ID_HEADER));
        configuration.setExposedHeaders(List.of(CorrelationIdFilter.TRACE_ID_HEADER));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse response, ApiErrorCode code)
            throws java.io.IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                ApiError.of(code, CorrelationIdFilter.currentTraceId()));
    }
}
