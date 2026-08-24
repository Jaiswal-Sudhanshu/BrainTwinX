package com.braintwinx.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT settings, validated at startup.
 *
 * <p>The secret has <strong>no default</strong>. A missing value must abort startup rather than
 * fall back to something guessable — an application running on a default signing key is worse
 * than one that refuses to run, because the failure is silent.
 *
 * <p>{@link #validate()} additionally rejects secrets that are too short for HS256 or that match
 * a known placeholder. A 256-bit minimum is not merely advisory: HMAC-SHA256 security degrades to
 * the key length, and the placeholder check exists because copying {@code .env.example} verbatim
 * is the single most likely way a weak key reaches a deployment.
 */
@Validated
@ConfigurationProperties(prefix = "braintwinx.jwt")
public class JwtProperties {

    /** Minimum 32 bytes = 256 bits, matching the HS256 output size. */
    static final int MIN_SECRET_BYTES = 32;

    private static final Set<String> FORBIDDEN_SECRET_MARKERS = Set.of(
            "change_me", "changeme", "placeholder", "secret", "example",
            "generate_a_256_bit_random_secret", "your-secret", "todo");

    @NotBlank
    private String secret;

    @Min(60)
    private long accessTokenTtlSeconds = 900;          // 15 minutes

    @Min(300)
    private long refreshTokenTtlSeconds = 604_800;     // 7 days

    @NotBlank
    private String issuer = "braintwinx";

    /**
     * Fails fast on a weak or placeholder secret.
     *
     * @throws IllegalStateException if the secret is too short or looks like a placeholder
     */
    public void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "braintwinx.jwt.secret is not set. Supply JWT_SECRET; generate one with "
                            + "'openssl rand -base64 48'. The application will not start without it.");
        }
        int byteLength = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (byteLength < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "braintwinx.jwt.secret is too short: %d bytes, minimum %d (256 bits) for HS256. "
                            .formatted(byteLength, MIN_SECRET_BYTES)
                            + "Generate one with 'openssl rand -base64 48'.");
        }
        String normalised = secret.toLowerCase(java.util.Locale.ROOT);
        for (String marker : FORBIDDEN_SECRET_MARKERS) {
            if (normalised.contains(marker)) {
                // The offending value is never included in the message: it would be logged.
                throw new IllegalStateException(
                        "braintwinx.jwt.secret appears to be a placeholder rather than a real "
                                + "secret. Generate one with 'openssl rand -base64 48'.");
            }
        }
        if (refreshTokenTtlSeconds <= accessTokenTtlSeconds) {
            throw new IllegalStateException(
                    "braintwinx.jwt.refresh-token-ttl-seconds must exceed access-token-ttl-seconds, "
                            + "otherwise refreshing cannot extend a session.");
        }
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public void setAccessTokenTtlSeconds(long accessTokenTtlSeconds) {
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    public long getRefreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }

    public void setRefreshTokenTtlSeconds(long refreshTokenTtlSeconds) {
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /** Never includes the secret. */
    @Override
    public String toString() {
        return "JwtProperties{issuer=" + issuer
                + ", accessTokenTtlSeconds=" + accessTokenTtlSeconds
                + ", refreshTokenTtlSeconds=" + refreshTokenTtlSeconds
                + ", secret=[REDACTED]}";
    }
}
