package com.braintwinx.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Base for append-only entities: a surrogate key and a creation timestamp.
 *
 * <p>Used by the inference and reporting tables, which are never updated in place.
 * Re-analysis appends a new row so that an already-issued report remains explainable
 * by the exact record it was built from.
 *
 * <p>{@code createdAt} is populated by JPA auditing rather than by each service, which
 * removes a class of bug where a new write path silently forgets a timestamp.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Exposed for tests and fixtures only; production code lets JPA assign the key. */
    protected void setId(Long id) {
        this.id = id;
    }

    protected void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Identity is based on the persisted key only. Two unsaved instances are never
     * equal, which keeps a transient entity from colliding with a persisted one in a
     * collection.
     */
    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BaseEntity that)) {
            return false;
        }
        // Distinct classes may share an id space; require the same effective type.
        if (!getClass().equals(that.getClass())) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public final int hashCode() {
        return id != null ? id.hashCode() : Objects.hashCode(getClass());
    }
}
