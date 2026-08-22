package com.braintwinx.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An authenticated principal.
 *
 * <p>Only a hash of the password is stored. There is deliberately no accessor or
 * {@code toString()} path that could carry the hash into a log line (brief section 7).
 */
@Entity
@Table(name = "users")
public class User extends MutableEntity {

    /** Opaque identifier used in APIs; the sequential key is never exposed. */
    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /** BCrypt hash. Never plaintext, never logged, never serialised to a client. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 128)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Role role;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    /** Non-null while the account is temporarily locked after repeated failures. */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() {
        // Required by JPA.
    }

    public User(String username, String email, String passwordHash, String fullName, Role role) {
        this.publicId = UUID.randomUUID().toString();
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.enabled = true;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void changePasswordHash(String newHash) {
        this.passwordHash = newHash;
    }

    public void recordSuccessfulLogin(Instant at) {
        this.lastLoginAt = at;
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    public void recordFailedLogin() {
        this.failedLoginAttempts++;
    }

    public void lockUntil(Instant until) {
        this.lockedUntil = until;
    }

    /** @return {@code true} if the account is locked at the given instant. */
    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Excludes every credential-bearing field by construction. */
    @Override
    public String toString() {
        return "User{publicId=" + publicId + ", username=" + username + ", role=" + role + "}";
    }
}
