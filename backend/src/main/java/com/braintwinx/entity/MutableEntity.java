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

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    protected void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
