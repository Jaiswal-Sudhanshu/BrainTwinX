package com.braintwinx.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A rotating refresh token.
 *
 * <p>Only a SHA-256 hash of the token is persisted, so a database disclosure yields no
 * usable credential. Rotation is recorded via {@code replacedBy}, which turns reuse of a
 * superseded token into a detectable event rather than a silent success.
 *
 * <p>Intentionally does not extend {@link BaseEntity}: its lifecycle columns are
 * {@code issued_at} / {@code expires_at} rather than a generic created/updated pair.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** SHA-256 hex digest of the token value. The token itself is never stored. */
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by")
    private RefreshToken replacedBy;

    protected RefreshToken() {
        // Required by JPA.
    }

    public RefreshToken(User user, String tokenHash, Instant issuedAt, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public RefreshToken getReplacedBy() {
        return replacedBy;
    }

    public void revoke(Instant at) {
        if (this.revokedAt == null) {
            this.revokedAt = at;
        }
    }

    public void replaceWith(RefreshToken successor, Instant at) {
        this.replacedBy = successor;
        revoke(at);
    }

    /** @return {@code true} if this token may still be exchanged at the given instant. */
    public boolean isUsableAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    /** Never includes the hash. */
    @Override
    public String toString() {
        return "RefreshToken{id=" + id + ", expiresAt=" + expiresAt
                + ", revoked=" + (revokedAt != null) + "}";
    }
}
