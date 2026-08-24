package com.braintwinx.audit;

import com.braintwinx.config.CorrelationIdFilter;
import com.braintwinx.entity.AuditAction;
import com.braintwinx.entity.AuditLog;
import com.braintwinx.entity.User;
import com.braintwinx.repository.AuditLogRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records auditable events (project brief section 27).
 *
 * <p><strong>Metadata is allow-listed, never passed through.</strong> {@link #ALLOWED_METADATA_KEYS}
 * is the complete set of keys that may be persisted, and anything else is dropped. This is the
 * structural defence that stops a field added to some future request DTO from leaking into the
 * audit log by default — the alternative, serialising a request body, would eventually write a
 * password, a token, or patient data into a table that is deliberately impossible to delete from.
 *
 * <p>Values are additionally length-capped, because an audit row must not become an avenue for
 * unbounded attacker-controlled storage.
 *
 * <p>Audit writes use {@code REQUIRES_NEW} so that a failure to record an event cannot roll back
 * the business operation, and equally so that a rolled-back business operation still leaves
 * evidence it was attempted. A failed audit write is logged loudly but never propagated: losing
 * an audit row is bad, but failing a clinician's action because the audit table hiccuped is worse.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /**
     * The complete set of metadata keys that may be persisted.
     *
     * <p>Deliberately excludes anything credential-bearing or patient-identifying. Adding a key
     * here is a decision that should be reviewed, which is exactly why it is a constant rather
     * than a convention.
     */
    static final Set<String> ALLOWED_METADATA_KEYS = Set.of(
            "reason",            // why an action failed, from a closed vocabulary
            "errorCode",         // ApiErrorCode name
            "scanStatus",
            "jobStatus",
            "modelName",
            "modelVersion",
            "preprocessingVersion",
            "observationCount",
            "durationMs",
            "attemptCount",
            "previousRole",      // for USER_ROLE_CHANGED
            "newRole",
            "isSynthetic");      // so stub-derived results are visible in the trail

    private static final int MAX_VALUE_LENGTH = 200;
    private static final int MAX_USER_AGENT_LENGTH = 255;

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records an event.
     *
     * @param user       authenticated principal, or {@code null} where there is none
     * @param username   denormalised so the trail stays readable if the user row is removed;
     *                   for a failed login this is the attempted username
     * @param resourceId the resource's PUBLIC identifier — never an internal sequential id
     * @param metadata   filtered against {@link #ALLOWED_METADATA_KEYS}; may be {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(User user,
                       String username,
                       AuditAction action,
                       String resourceType,
                       String resourceId,
                       boolean success,
                       Map<String, Object> metadata,
                       HttpServletRequest request) {
        try {
            AuditLog entry = new AuditLog(
                    user,
                    truncate(username, 64),
                    action,
                    truncate(resourceType, 64),
                    truncate(resourceId, 64),
                    success,
                    request != null ? clientIp(request) : null,
                    request != null ? truncate(request.getHeader("User-Agent"), MAX_USER_AGENT_LENGTH) : null,
                    CorrelationIdFilter.currentTraceId(),
                    serialiseMetadata(metadata),
                    Instant.now());
            auditLogRepository.save(entry);
        } catch (RuntimeException ex) {
            // Never propagate: an audit failure must not fail the audited operation.
            log.error("Failed to record audit event {} for resource {}/{}", action, resourceType,
                    resourceId, ex);
        }
    }

    /** Convenience overload for events with no resource and no metadata. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(User user, String username, AuditAction action, boolean success,
                       HttpServletRequest request) {
        record(user, username, action, null, null, success, null, request);
    }

    /**
     * Filters metadata down to allow-listed keys and caps value length.
     *
     * @return JSON, or {@code null} when nothing survived filtering
     */
    private String serialiseMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (!ALLOWED_METADATA_KEYS.contains(key) || value == null) {
                return;
            }
            if (value instanceof Number || value instanceof Boolean) {
                filtered.put(key, value);
            } else {
                filtered.put(key, truncate(value.toString(), MAX_VALUE_LENGTH));
            }
        });
        if (filtered.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(filtered);
        } catch (JacksonException ex) {
            log.warn("Could not serialise audit metadata; dropping it");
            return null;
        }
    }

    /**
     * Resolves the client IP.
     *
     * <p>Uses only {@code getRemoteAddr()}. {@code X-Forwarded-For} is deliberately NOT trusted
     * here: it is client-supplied and trivially spoofed, so recording it as fact would put
     * attacker-chosen values into the audit trail. In production the reverse proxy is expected to
     * set the real remote address, which Spring Boot honours via
     * {@code server.forward-headers-strategy=native}.
     */
    private String clientIp(HttpServletRequest request) {
        return truncate(request.getRemoteAddr(), 45);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\r\\n]", " ");   // no log/record injection
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }
}
