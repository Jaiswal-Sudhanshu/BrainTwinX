package com.braintwinx.security;

import com.braintwinx.entity.Role;
import com.braintwinx.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies JWTs, and mints refresh tokens.
 *
 * <p>Design points that matter for security:
 *
 * <ul>
 *   <li><strong>The signing algorithm is fixed at HS256 on both issue and verification.</strong>
 *       Verification builds a parser bound to the key, so a token whose header claims a different
 *       algorithm — the classic {@code alg} confusion and {@code alg:none} attacks — cannot be
 *       accepted.</li>
 *   <li><strong>Issuer is verified, not merely set.</strong> A validly signed token from another
 *       system sharing the key would otherwise be accepted.</li>
 *   <li><strong>Expiry is distinguished from invalidity.</strong> A client needs to know whether
 *       to refresh or to re-authenticate, so the two map to different error codes. This is a
 *       deliberate exception to otherwise-uniform auth responses: it reveals nothing about
 *       whether an account exists.</li>
 *   <li><strong>The subject is the opaque {@code publicId}, never the database id.</strong> A
 *       token is client-visible, so it must not carry an internal sequential identifier
 *       (brief section 26).</li>
 *   <li><strong>Only a SHA-256 hash of a refresh token is ever persisted</strong>, so a database
 *       disclosure yields nothing usable.</li>
 * </ul>
 *
 * <p>No method here logs a token, a hash, or the signing key.
 */
@Service
public class JwtService {

    /** Claim carrying the role. Kept short — JWTs travel on every request. */
    static final String CLAIM_ROLE = "rol";

    /** Distinguishes an access token from a refresh token so the two cannot be interchanged. */
    static final String CLAIM_TOKEN_TYPE = "typ";
    static final String TOKEN_TYPE_ACCESS = "access";

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final JwtProperties properties;
    private final SecretKey signingKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public JwtService(JwtProperties properties) {
        properties.validate();
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(
                properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Issues a short-lived access token.
     *
     * @param user the authenticated principal
     * @param now  supplied rather than read from the clock so tests are deterministic
     */
    public String issueAccessToken(User user, Instant now) {
        Instant expiry = now.plusSeconds(properties.getAccessTokenTtlSeconds());
        return Jwts.builder()
                .issuer(properties.getIssuer())
                .subject(user.getPublicId())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifies an access token.
     *
     * @return the parsed principal
     * @throws com.braintwinx.exception.AuthenticationFailedException with
     *         {@code TOKEN_EXPIRED} or {@code TOKEN_INVALID}
     */
    public AuthenticatedPrincipal verifyAccessToken(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    // Rejects a token signed with, or claiming, any other algorithm.
                    .sig().add(Jwts.SIG.HS256).and()
                    .requireIssuer(properties.getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw new com.braintwinx.exception.AuthenticationFailedException(
                    com.braintwinx.exception.ApiErrorCode.TOKEN_EXPIRED, "Access token expired");
        } catch (JwtException | IllegalArgumentException ex) {
            // Covers a bad signature, malformed token, wrong algorithm, and wrong issuer.
            throw new com.braintwinx.exception.AuthenticationFailedException(
                    com.braintwinx.exception.ApiErrorCode.TOKEN_INVALID,
                    "Access token rejected: " + ex.getClass().getSimpleName());
        }

        if (!TOKEN_TYPE_ACCESS.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
            // Prevents a refresh token being presented as an access token.
            throw new com.braintwinx.exception.AuthenticationFailedException(
                    com.braintwinx.exception.ApiErrorCode.TOKEN_INVALID, "Wrong token type");
        }

        String publicId = claims.getSubject();
        Role role = parseRole(claims.get(CLAIM_ROLE, String.class));
        if (publicId == null || publicId.isBlank() || role == null) {
            throw new com.braintwinx.exception.AuthenticationFailedException(
                    com.braintwinx.exception.ApiErrorCode.TOKEN_INVALID, "Incomplete token claims");
        }
        return new AuthenticatedPrincipal(publicId, role);
    }

    /**
     * Mints an opaque refresh token.
     *
     * <p>Deliberately <em>not</em> a JWT: a refresh token must be revocable, and revocation
     * requires server-side state. 256 bits from a CSPRNG, URL-safe encoded.
     *
     * @return the raw token, which is returned to the client exactly once and never stored
     */
    public String generateRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * @return the SHA-256 hex digest of a refresh token — the only form ever persisted
     */
    public String hashRefreshToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JCA spec; absence means a broken JVM.
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public Duration accessTokenTtl() {
        return Duration.ofSeconds(properties.getAccessTokenTtlSeconds());
    }

    public Duration refreshTokenTtl() {
        return Duration.ofSeconds(properties.getRefreshTokenTtlSeconds());
    }

    private Role parseRole(String raw) {
        if (raw == null) {
            return null;
        }
        return Optional.of(raw)
                .flatMap(value -> {
                    for (Role role : Role.values()) {
                        if (role.name().equals(value)) {
                            return Optional.of(role);
                        }
                    }
                    return Optional.empty();
                })
                .orElse(null);
    }

    /** The verified identity carried by an access token. */
    public record AuthenticatedPrincipal(String publicId, Role role) {
    }
}
