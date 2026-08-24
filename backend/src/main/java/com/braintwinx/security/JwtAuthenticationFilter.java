package com.braintwinx.security;

import com.braintwinx.exception.ApiError;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.AuthenticationFailedException;
import com.braintwinx.config.CorrelationIdFilter;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request from its {@code Authorization: Bearer} header.
 *
 * <p>Behaviour when no token is present: the filter does nothing and passes the request on. It
 * does <strong>not</strong> reject. Rejection is Spring Security's job via the filter chain's
 * authorisation rules, which are deny-by-default — so an endpoint is never accidentally left open
 * because this filter forgot to guard it.
 *
 * <p>Behaviour when a token is present but bad: the request is rejected immediately with the
 * standard error envelope. A malformed or expired token is an explicit failure, not an anonymous
 * request, and treating it as anonymous would mask token problems as puzzling 403s.
 *
 * <p>Expired and invalid are reported distinctly so a client knows whether to refresh or
 * re-authenticate. That leaks nothing about whether an account exists.
 *
 * <p>The token value is never logged.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractBearerToken(request);
        if (token == null) {
            // Anonymous. The authorisation rules decide whether that is acceptable.
            filterChain.doFilter(request, response);
            return;
        }

        try {
            JwtService.AuthenticatedPrincipal principal = jwtService.verifyAccessToken(token);

            var authorities = List.of(new SimpleGrantedAuthority(principal.role().authority()));
            var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);
        } catch (AuthenticationFailedException ex) {
            // Cleared so a partially populated context cannot leak into the response cycle.
            SecurityContextHolder.clearContext();
            log.warn("Rejected bearer token at {} {}: {}", request.getMethod(),
                    request.getRequestURI(), ex.getMessage());
            writeError(response, ex.getErrorCode());
        }
    }

    /**
     * @return the bearer token, or {@code null} if the header is absent or not a bearer header
     */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Writes the standard error envelope directly.
     *
     * <p>Necessary because a filter runs outside {@code @RestControllerAdvice}, so the exception
     * handler cannot format this response. The shape is kept identical to every other error so
     * clients have one contract.
     */
    private void writeError(HttpServletResponse response, ApiErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiError body = ApiError.of(code, CorrelationIdFilter.currentTraceId());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
