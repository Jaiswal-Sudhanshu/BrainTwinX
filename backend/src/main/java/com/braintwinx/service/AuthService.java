package com.braintwinx.service;

import com.braintwinx.audit.AuditService;
import com.braintwinx.dto.LoginRequest;
import com.braintwinx.dto.TokenResponse;
import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.RefreshToken;
import com.braintwinx.entity.User;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.AuthenticationFailedException;
import com.braintwinx.repository.RefreshTokenRepository;
import com.braintwinx.repository.UserRepository;
import com.braintwinx.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login, refresh, and logout.
 *
 * <p>Several behaviours here are security-motivated rather than obvious:
 *
 * <ul>
 *   <li><strong>Uniform failure.</strong> An unknown username, a wrong password, a disabled
 *       account, and a locked account all produce the same {@code UNAUTHORIZED} response. The real
 *       reason goes to the audit trail and the log only, so the endpoint cannot be used to
 *       enumerate users or probe account state.</li>
 *   <li><strong>A dummy hash is verified when the user does not exist.</strong> Without it, a
 *       missing user returns markedly faster than a wrong password, and that timing difference is
 *       itself a user-enumeration oracle.</li>
 *   <li><strong>Refresh tokens rotate.</strong> Each exchange revokes the presented token and
 *       links it to its successor, so replaying a consumed token is detectable rather than
 *       silently successful.</li>
 *   <li><strong>Reuse of an already-consumed token revokes the whole family.</strong> Reuse means
 *       either a bug or a stolen token; the safe response is to end every session for that user
 *       and force re-authentication.</li>
 *   <li><strong>Lockout is temporary, not permanent.</strong> A permanent lock would let an
 *       attacker deny a clinician access to patient records by guessing passwords — a denial-of-care
 *       risk that outweighs the marginal benefit over a timed lock.</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    /**
     * A real BCrypt hash of a value no one knows, verified when the username does not exist so
     * that the response time is comparable to a genuine wrong-password attempt.
     */
    private static final String DUMMY_HASH =
            "$2a$12$Q9k5uJ0oQ8vXzY1wKq3rZeH5Xk8fN2mLpO7sT4vB6cD8eF0gH2iJk";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    /**
     * Authenticates and issues tokens.
     *
     * @throws AuthenticationFailedException always with {@code UNAUTHORIZED}, whatever the reason
     */
    @Transactional
    public TokenResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        Instant now = Instant.now();
        Optional<User> maybeUser = userRepository.findByUsername(request.username());

        if (maybeUser.isEmpty()) {
            // Constant-time-ish: do the same work as a real verification.
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            auditFailure(null, request.username(), "UNKNOWN_USERNAME", httpRequest);
            throw new AuthenticationFailedException("Unknown username: " + request.username());
        }

        User user = maybeUser.get();

        if (user.isLockedAt(now)) {
            auditFailure(user, user.getUsername(), "ACCOUNT_LOCKED", httpRequest);
            throw new AuthenticationFailedException("Account locked until " + user.getLockedUntil());
        }
        if (!user.isEnabled()) {
            auditFailure(user, user.getUsername(), "ACCOUNT_DISABLED", httpRequest);
            throw new AuthenticationFailedException("Account disabled: " + user.getUsername());
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.recordFailedLogin();
            String reason = "BAD_CREDENTIALS";
            if (user.getFailedLoginAttempts() >= MAX_FAILED_ATTEMPTS) {
                user.lockUntil(now.plus(LOCKOUT_DURATION));
                reason = "LOCKED_AFTER_REPEATED_FAILURES";
                log.warn("Account {} locked after {} failed attempts", user.getUsername(),
                        user.getFailedLoginAttempts());
            }
            userRepository.save(user);
            auditFailure(user, user.getUsername(), reason, httpRequest);
            throw new AuthenticationFailedException("Bad credentials for " + user.getUsername());
        }

        user.recordSuccessfulLogin(now);
        userRepository.save(user);

        String rawRefreshToken = issueRefreshToken(user, now, null);
        String accessToken = jwtService.issueAccessToken(user, now);

        auditService.record(user, user.getUsername(), AuditAction.LOGIN, true, httpRequest);

        return TokenResponse.bearer(accessToken, rawRefreshToken,
                jwtService.accessTokenTtl().toSeconds(), user.getPublicId(), user.getUsername(),
                user.getFullName(), user.getRole().name());
    }

    /**
     * Exchanges a refresh token for a new token pair, rotating the refresh token.
     *
     * @throws AuthenticationFailedException if the token is unknown, expired, or already consumed
     */
    @Transactional
    public TokenResponse refresh(String rawRefreshToken, HttpServletRequest httpRequest) {
        Instant now = Instant.now();
        String hash = jwtService.hashRefreshToken(rawRefreshToken);

        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new AuthenticationFailedException(
                        ApiErrorCode.TOKEN_INVALID, "Unknown refresh token presented"));

        User user = stored.getUser();

        if (stored.getRevokedAt() != null) {
            // Reuse of a consumed token: either a bug or theft. End every session for safety.
            int revoked = refreshTokenRepository.revokeAllForUser(user, now);
            log.warn("Refresh token reuse detected for {}; revoked {} token(s)",
                    user.getUsername(), revoked);
            auditFailure(user, user.getUsername(), "REFRESH_TOKEN_REUSE", httpRequest);
            throw new AuthenticationFailedException(
                    ApiErrorCode.TOKEN_INVALID, "Refresh token already consumed");
        }
        if (!stored.isUsableAt(now)) {
            auditFailure(user, user.getUsername(), "REFRESH_TOKEN_EXPIRED", httpRequest);
            throw new AuthenticationFailedException(
                    ApiErrorCode.TOKEN_EXPIRED, "Refresh token expired");
        }
        if (!user.isEnabled() || user.isLockedAt(now)) {
            // A valid token must not outlive the account's right to use it.
            refreshTokenRepository.revokeAllForUser(user, now);
            auditFailure(user, user.getUsername(), "ACCOUNT_NOT_ACTIVE", httpRequest);
            throw new AuthenticationFailedException("Account is not active");
        }

        String newRawToken = issueRefreshToken(user, now, stored);
        String accessToken = jwtService.issueAccessToken(user, now);

        auditService.record(user, user.getUsername(), AuditAction.TOKEN_REFRESHED, true, httpRequest);

        return TokenResponse.bearer(accessToken, newRawToken,
                jwtService.accessTokenTtl().toSeconds(), user.getPublicId(), user.getUsername(),
                user.getFullName(), user.getRole().name());
    }

    /**
     * Revokes every refresh token for the user.
     *
     * <p>Access tokens are stateless and remain valid until they expire — which is why the access
     * TTL is short. Revoking refresh tokens ends the session's ability to renew itself.
     *
     * <p>Idempotent: logging out twice is not an error.
     */
    @Transactional
    public void logout(String publicId, HttpServletRequest httpRequest) {
        userRepository.findByPublicId(publicId).ifPresent(user -> {
            int revoked = refreshTokenRepository.revokeAllForUser(user, Instant.now());
            log.debug("Logout for {} revoked {} refresh token(s)", user.getUsername(), revoked);
            auditService.record(user, user.getUsername(), AuditAction.LOGOUT, true, httpRequest);
        });
    }

    /**
     * Creates and persists a refresh token, returning the raw value.
     *
     * @param predecessor the token being rotated, or {@code null} for a fresh login
     * @return the raw token — the caller returns it to the client; only its hash is stored
     */
    private String issueRefreshToken(User user, Instant now, RefreshToken predecessor) {
        String raw = jwtService.generateRefreshToken();
        RefreshToken token = new RefreshToken(user, jwtService.hashRefreshToken(raw), now,
                now.plus(jwtService.refreshTokenTtl()));
        RefreshToken saved = refreshTokenRepository.save(token);
        if (predecessor != null) {
            predecessor.replaceWith(saved, now);
            refreshTokenRepository.save(predecessor);
        }
        return raw;
    }

    /**
     * Records a failed authentication.
     *
     * <p>The reason is drawn from a closed vocabulary and goes into allow-listed metadata, so the
     * trail explains what happened without the response revealing it.
     */
    private void auditFailure(User user, String username, String reason,
                              HttpServletRequest httpRequest) {
        auditService.record(user, username, AuditAction.LOGIN_FAILED, null, null, false,
                Map.of("reason", reason), httpRequest);
    }
}
