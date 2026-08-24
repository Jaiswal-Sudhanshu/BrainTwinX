package com.braintwinx.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.braintwinx.entity.Role;
import com.braintwinx.entity.User;
import com.braintwinx.exception.ApiErrorCode;
import com.braintwinx.exception.AuthenticationFailedException;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies JWT issuing, verification, and configuration validation.
 *
 * <p>These are the tests that matter most in Phase 3: they assert the token layer rejects the
 * classic JWT attacks rather than merely that the happy path works.
 */
@DisplayName("JwtService")
class JwtServiceTest {

    private static final String STRONG_SECRET =
            "3f9a2b7c1d4e6f8a0b2c4d6e8f0a1b3c5d7e9f1a3b5c7d9e1f3a5b7c9d1e3f5a";

    private JwtService jwtService;
    private User user;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(propertiesWith(STRONG_SECRET, 900, 604_800));
        user = new User("dr.smith", "smith@example.invalid", "irrelevant-hash",
                "Dr Smith", Role.DOCTOR);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("access tokens")
    class AccessTokens {

        @Test
        @DisplayName("a freshly issued token verifies and carries the expected identity")
        void roundTrip() {
            String token = jwtService.issueAccessToken(user, Instant.now());

            JwtService.AuthenticatedPrincipal principal = jwtService.verifyAccessToken(token);

            assertThat(principal.publicId()).isEqualTo(user.getPublicId());
            assertThat(principal.role()).isEqualTo(Role.DOCTOR);
        }

        @Test
        @DisplayName("the subject is the opaque publicId, never the database id")
        void subjectIsPublicId() {
            String token = jwtService.issueAccessToken(user, Instant.now());

            // A JWT is client-visible, so an internal sequential id must never appear in it.
            assertThat(jwtService.verifyAccessToken(token).publicId())
                    .isEqualTo(user.getPublicId())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }

        @Test
        @DisplayName("an expired token is reported as TOKEN_EXPIRED, distinctly from invalid")
        void expiredTokenIsDistinguishable() {
            // A client must be able to tell "refresh me" from "re-authenticate".
            Instant wellInThePast = Instant.now().minus(2, ChronoUnit.HOURS);
            String token = jwtService.issueAccessToken(user, wellInThePast);

            assertThatThrownBy(() -> jwtService.verifyAccessToken(token))
                    .isInstanceOf(AuthenticationFailedException.class)
                    .extracting(ex -> ((AuthenticationFailedException) ex).getErrorCode())
                    .isEqualTo(ApiErrorCode.TOKEN_EXPIRED);
        }

        @Test
        @DisplayName("a token signed with a different key is rejected")
        void wrongSigningKeyRejected() {
            JwtService other = new JwtService(propertiesWith(
                    "0000111122223333444455556666777788889999aaaabbbbccccddddeeeeffff", 900, 604_800));
            String foreignToken = other.issueAccessToken(user, Instant.now());

            assertThatThrownBy(() -> jwtService.verifyAccessToken(foreignToken))
                    .isInstanceOf(AuthenticationFailedException.class)
                    .extracting(ex -> ((AuthenticationFailedException) ex).getErrorCode())
                    .isEqualTo(ApiErrorCode.TOKEN_INVALID);
        }

        @Test
        @DisplayName("an unsigned token (alg:none) is rejected")
        void unsignedTokenRejected() {
            // The classic alg:none attack. Verification is bound to HS256 and to the key, so an
            // unsigned token cannot be accepted no matter what its header claims.
            String unsigned = Jwts.builder()
                    .issuer("braintwinx")
                    .subject(user.getPublicId())
                    .claim(JwtService.CLAIM_ROLE, Role.ADMIN.name())
                    .claim(JwtService.CLAIM_TOKEN_TYPE, JwtService.TOKEN_TYPE_ACCESS)
                    .expiration(Date.from(Instant.now().plusSeconds(600)))
                    .compact();

            assertThatThrownBy(() -> jwtService.verifyAccessToken(unsigned))
                    .isInstanceOf(AuthenticationFailedException.class);
        }

        @Test
        @DisplayName("a token from another issuer is rejected even when validly signed")
        void wrongIssuerRejected() {
            // Same key, different issuer: would be accepted if the issuer were merely set and
            // never checked.
            SecretKey key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                    STRONG_SECRET.getBytes(StandardCharsets.UTF_8));
            String foreign = Jwts.builder()
                    .issuer("some-other-system")
                    .subject(user.getPublicId())
                    .claim(JwtService.CLAIM_ROLE, Role.ADMIN.name())
                    .claim(JwtService.CLAIM_TOKEN_TYPE, JwtService.TOKEN_TYPE_ACCESS)
                    .expiration(Date.from(Instant.now().plusSeconds(600)))
                    .signWith(key, Jwts.SIG.HS256)
                    .compact();

            assertThatThrownBy(() -> jwtService.verifyAccessToken(foreign))
                    .isInstanceOf(AuthenticationFailedException.class)
                    .extracting(ex -> ((AuthenticationFailedException) ex).getErrorCode())
                    .isEqualTo(ApiErrorCode.TOKEN_INVALID);
        }

        @Test
        @DisplayName("a token missing the access type claim is rejected")
        void wrongTokenTypeRejected() {
            // Stops a token minted for another purpose being replayed as an access token.
            SecretKey key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                    STRONG_SECRET.getBytes(StandardCharsets.UTF_8));
            String noType = Jwts.builder()
                    .issuer("braintwinx")
                    .subject(user.getPublicId())
                    .claim(JwtService.CLAIM_ROLE, Role.ADMIN.name())
                    .expiration(Date.from(Instant.now().plusSeconds(600)))
                    .signWith(key, Jwts.SIG.HS256)
                    .compact();

            assertThatThrownBy(() -> jwtService.verifyAccessToken(noType))
                    .isInstanceOf(AuthenticationFailedException.class);
        }

        @Test
        @DisplayName("a token carrying an unknown role is rejected, not defaulted")
        void unknownRoleRejected() {
            // Must never silently fall back to some role: that would be a privilege decision
            // made by malformed input.
            SecretKey key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                    STRONG_SECRET.getBytes(StandardCharsets.UTF_8));
            String bogusRole = Jwts.builder()
                    .issuer("braintwinx")
                    .subject(user.getPublicId())
                    .claim(JwtService.CLAIM_ROLE, "SUPER_ADMIN")
                    .claim(JwtService.CLAIM_TOKEN_TYPE, JwtService.TOKEN_TYPE_ACCESS)
                    .expiration(Date.from(Instant.now().plusSeconds(600)))
                    .signWith(key, Jwts.SIG.HS256)
                    .compact();

            assertThatThrownBy(() -> jwtService.verifyAccessToken(bogusRole))
                    .isInstanceOf(AuthenticationFailedException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "not-a-jwt", "a.b.c", "..", "eyJhbGciOiJIUzI1NiJ9"})
        @DisplayName("malformed input is rejected without leaking an internal exception")
        void malformedTokensRejected(String malformed) {
            assertThatThrownBy(() -> jwtService.verifyAccessToken(malformed))
                    .isInstanceOf(AuthenticationFailedException.class);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("refresh tokens")
    class RefreshTokens {

        @Test
        @DisplayName("generated tokens are unique and high-entropy")
        void tokensAreUnique() {
            var tokens = new java.util.HashSet<String>();
            for (int i = 0; i < 500; i++) {
                tokens.add(jwtService.generateRefreshToken());
            }
            assertThat(tokens).hasSize(500);
            // 32 bytes base64url without padding = 43 characters.
            assertThat(tokens).allSatisfy(t -> assertThat(t).hasSize(43));
        }

        @Test
        @DisplayName("hashing is deterministic and produces a SHA-256 hex digest")
        void hashingIsStable() {
            String raw = jwtService.generateRefreshToken();

            String first = jwtService.hashRefreshToken(raw);
            String second = jwtService.hashRefreshToken(raw);

            assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("the hash does not reveal the token")
        void hashDiffersFromRaw() {
            String raw = jwtService.generateRefreshToken();
            assertThat(jwtService.hashRefreshToken(raw)).isNotEqualTo(raw).doesNotContain(raw);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("configuration validation")
    class ConfigValidation {

        @Test
        @DisplayName("a strong secret is accepted")
        void strongSecretAccepted() {
            assertThatCode(() -> new JwtService(propertiesWith(STRONG_SECRET, 900, 604_800)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a missing secret aborts startup")
        void missingSecretRejected() {
            assertThatThrownBy(() -> new JwtService(propertiesWith(null, 900, 604_800)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not set");
        }

        @Test
        @DisplayName("a secret under 256 bits aborts startup")
        void shortSecretRejected() {
            assertThatThrownBy(() -> new JwtService(propertiesWith("tooshort", 900, 604_800)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("too short");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "CHANGE_ME_GENERATE_A_256_BIT_RANDOM_SECRET_PADDING_TO_LENGTH",
                "this-is-a-placeholder-value-long-enough-to-pass-the-length-check",
                "my-super-secret-key-that-is-definitely-long-enough-to-pass-check"
        })
        @DisplayName("a placeholder secret aborts startup even when long enough")
        void placeholderSecretRejected(String placeholder) {
            // Copying .env.example verbatim is the most likely way a weak key reaches a
            // deployment, so length alone is not sufficient.
            assertThatThrownBy(() -> new JwtService(propertiesWith(placeholder, 900, 604_800)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("placeholder");
        }

        @Test
        @DisplayName("a refresh TTL not exceeding the access TTL aborts startup")
        void inconsistentTtlsRejected() {
            assertThatThrownBy(() -> new JwtService(propertiesWith(STRONG_SECRET, 900, 900)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must exceed");
        }

        @Test
        @DisplayName("toString never reveals the secret")
        void toStringRedactsSecret() {
            JwtProperties properties = propertiesWith(STRONG_SECRET, 900, 604_800);

            assertThat(properties.toString())
                    .contains("[REDACTED]")
                    .doesNotContain(STRONG_SECRET);
        }
    }

    // ------------------------------------------------------------------

    private static JwtProperties propertiesWith(String secret, long accessTtl, long refreshTtl) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setAccessTokenTtlSeconds(accessTtl);
        properties.setRefreshTokenTtlSeconds(refreshTtl);
        properties.setIssuer("braintwinx");
        return properties;
    }
}
