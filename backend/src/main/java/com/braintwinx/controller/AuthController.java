package com.braintwinx.controller;

import com.braintwinx.dto.LoginRequest;
import com.braintwinx.dto.RefreshRequest;
import com.braintwinx.dto.TokenResponse;
import com.braintwinx.security.JwtService;
import com.braintwinx.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints (project brief section 17).
 *
 * <p>Thin by design (brief section 49): it validates input, delegates to {@link AuthService}, and
 * shapes the response. No business logic, no persistence, no security decisions.
 *
 * <p>Responses carry {@code Cache-Control: no-store}. Tokens must not be written to a shared or
 * disk cache by an intermediary or the browser, and a default caching policy could otherwise
 * persist a credential-bearing response.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Authenticates and issues a token pair.
     *
     * <p>Public by necessity. Protected by lockout after repeated failures, and — from Phase 14 —
     * by rate limiting.
     *
     * @return 200 with tokens, or 401 with the standard envelope. The failure response is
     *         identical for an unknown user, a wrong password, and a disabled or locked account.
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        TokenResponse response = authService.login(request, httpRequest);
        return noStore().body(response);
    }

    /**
     * Exchanges a refresh token for a new pair, rotating the refresh token.
     *
     * @return 200 with a new pair, or 401. Presenting an already-consumed token additionally
     *         revokes every session for that user, since reuse implies a bug or theft.
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                                 HttpServletRequest httpRequest) {
        TokenResponse response = authService.refresh(request.refreshToken(), httpRequest);
        return noStore().body(response);
    }

    /**
     * Revokes every refresh token for the caller.
     *
     * <p>Requires authentication, so a logout cannot be forced on another user. Idempotent.
     *
     * <p>Already-issued access tokens remain valid until they expire — they are stateless, which is
     * why their TTL is deliberately short. This is documented rather than hidden because it is a
     * genuine property of the design a client must understand.
     *
     * @return 204
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal JwtService.AuthenticatedPrincipal principal,
            HttpServletRequest httpRequest) {
        if (principal != null) {
            authService.logout(principal.publicId(), httpRequest);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private ResponseEntity.BodyBuilder noStore() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache");
    }
}
