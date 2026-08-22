package com.braintwinx.repository;

import com.braintwinx.entity.RefreshToken;
import com.braintwinx.entity.User;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link RefreshToken}.
 *
 * <p>Lookup is by hash, never by the token value — the value is never stored, so it
 * cannot be queried by. Callers hash the presented token and look up the digest.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes every live token for a user. Used on logout and on password change, so a
     * stolen refresh token cannot outlive the credential it was issued against.
     */
    @Modifying
    @Query("""
            update RefreshToken t
               set t.revokedAt = :at
             where t.user = :user
               and t.revokedAt is null
            """)
    int revokeAllForUser(@Param("user") User user, @Param("at") Instant at);

    /**
     * Deletes tokens that expired before {@code cutoff}. Housekeeping only: expired
     * tokens are already unusable, so this bounds table growth rather than enforcing
     * security.
     */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
