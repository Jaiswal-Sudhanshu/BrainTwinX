package com.braintwinx.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import org.springframework.data.annotation.LastModifiedDate;

/**
 * Base for entities that change over their lifetime.
 *
 * <p>Adds a modification timestamp and an optimistic-lock version. The version matters
 * for correctness rather than performance: concurrent status transitions on a scan or
 * analysis job must not interleave and lose an update, which is how a scan could
 * otherwise end up in a state its own state machine forbids.
 */
@MappedSuperclass
public abstract class MutableEntity extends BaseEntity {

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic-lock counter.
     *
     * <p>Mapped to {@code lock_version}, not {@code version}, deliberately. "Version" is an
     * overloaded word in this domain — {@link ModelVersion} carries a semantic model version
     * string whose natural column name is {@code version} — and mapping an infrastructure
     * concern to that name collided with it. Naming the lock column explicitly keeps the
     * domain meaning of {@code version} available to entities that need it.
     */
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** @return the optimistic-lock counter, incremented by the persistence layer on update */
    public long getLockVersion() {
        return lockVersion;
    }

    protected void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
