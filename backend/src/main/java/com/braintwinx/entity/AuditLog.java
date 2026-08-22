package com.braintwinx.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An append-only audit entry (project brief section 27).
 *
 * <p>There is no setter, no update method, and no {@code updatedAt} column. The entity is
 * write-once by construction, and the repository exposes no delete operation, so the trail
 * cannot be rewritten by application code.
 *
 * <p><strong>Prohibited content.</strong> This row must never carry passwords, password
 * hashes, JWTs, refresh tokens, API keys, or patient-identifying free text. Two structural
 * defences support that rule:
 *
 * <ul>
 *   <li>{@code resourceId} holds a <em>public</em> identifier, never an internal
 *       sequential key.</li>
 *   <li>{@code metadata} is populated from an allow-list of keys by the audit service,
 *       never from an arbitrary request body — so a new field added to a request DTO
 *       cannot leak into the audit log by default.</li>
 * </ul>
 *
 * <p>{@code username} is denormalised so the trail stays readable even if the referenced
 * user row is later removed.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Null for events with no authenticated principal, e.g. a failed login. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false)
    private User user;

    @Column(name = "username", updatable = false, length = 64)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false, length = 64)
    private AuditAction action;

    @Column(name = "resource_type", updatable = false, length = 64)
    private String resourceType;

    /** Public identifier of the affected resource. Never an internal sequential id. */
    @Column(name = "resource_id", updatable = false, length = 64)
    private String resourceId;

    @Column(name = "success", nullable = false, updatable = false)
    private boolean success;

    @Column(name = "ip_address", updatable = false, length = 45)
    private String ipAddress;

    @Column(name = "user_agent", updatable = false, length = 255)
    private String userAgent;

    /** Correlates this entry with application log lines (brief section 28). */
    @Column(name = "trace_id", updatable = false, length = 64)
    private String traceId;

    /** Allow-listed keys only. Never a raw request body. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", updatable = false)
    private String metadataJson;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AuditLog() {
        // Required by JPA.
    }

    /**
     * @param user       authenticated principal, or {@code null} when there is none
     * @param username   denormalised username, retained even if the user row is removed
     * @param resourceId the resource's PUBLIC identifier, never an internal key
     */
    public AuditLog(User user,
                    String username,
                    AuditAction action,
                    String resourceType,
                    String resourceId,
                    boolean success,
                    String ipAddress,
                    String userAgent,
                    String traceId,
                    String metadataJson,
                    Instant occurredAt) {
        this.user = user;
        this.username = username;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.success = success;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.traceId = traceId;
        this.metadataJson = metadataJson;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getUsername() {
        return username;
    }

    public AuditAction getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    @Override
    public String toString() {
        return "AuditLog{id=" + id + ", action=" + action + ", success=" + success + "}";
    }
}
