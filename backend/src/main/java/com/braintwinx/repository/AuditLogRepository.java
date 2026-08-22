package com.braintwinx.repository;

import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.AuditLog;
import com.braintwinx.entity.User;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Persistence for {@link AuditLog}.
 *
 * <p><strong>Append-only by construction.</strong> This interface extends the bare
 * {@link Repository} marker rather than {@code JpaRepository} or {@code CrudRepository},
 * because those expose {@code delete}, {@code deleteAll}, and {@code deleteById}. An audit
 * trail that application code can delete is not an audit trail.
 *
 * <p>Only insertion and reads are declared here. Retention trimming, if it is ever
 * required, must be a deliberate, separately authorised database operation with its own
 * review — not a method any service can reach.
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    /** Inserts an entry. The entity exposes no mutators, so this only ever appends. */
    <S extends AuditLog> S save(S auditLog);

    /** Chronological review, newest first. */
    Page<AuditLog> findAllByOrderByOccurredAtDesc(Pageable pageable);

    /** Per-user trail. */
    Page<AuditLog> findByUserOrderByOccurredAtDesc(User user, Pageable pageable);

    /** Per-action filter, e.g. every REPORT_ACCESSED event. */
    Page<AuditLog> findByActionOrderByOccurredAtDesc(AuditAction action, Pageable pageable);

    /** "Who touched this resource" — takes the resource's PUBLIC identifier. */
    Page<AuditLog> findByResourceTypeAndResourceIdOrderByOccurredAtDesc(
            String resourceType, String resourceId, Pageable pageable);

    /** Time-window review. */
    Page<AuditLog> findByOccurredAtBetweenOrderByOccurredAtDesc(
            Instant from, Instant to, Pageable pageable);

    long count();
}
