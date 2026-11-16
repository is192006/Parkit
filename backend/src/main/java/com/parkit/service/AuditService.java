package com.parkit.service;

import com.parkit.domain.entity.AuditLog;
import com.parkit.repository.AuditLogRepository;
import com.parkit.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes audit entries (BR-27, FR-59) in the caller's transaction.
 *
 * <p>{@link Propagation#MANDATORY} makes it impossible to call this outside a
 * transaction: the audit row and the audited change commit or roll back
 * together, so the log never records an action that did not happen (or
 * misses one that did).
 */
@Service
public class AuditService {

    /** Audited entity types (audit_logs.entity_type). */
    public static final String ENTITY_SLOT = "PARKING_SLOT";
    public static final String ENTITY_SLOT_BLOCK = "SLOT_BLOCK";
    public static final String ENTITY_RATE = "PARKING_RATE";
    public static final String ENTITY_FACILITY = "FACILITY_SETTINGS";

    /** Audited actions (audit_logs.action). */
    public static final String SLOT_CREATED = "SLOT_CREATED";
    public static final String SLOT_UPDATED = "SLOT_UPDATED";
    public static final String SLOT_STATUS_CHANGED = "SLOT_STATUS_CHANGED";
    public static final String BLOCK_CREATED = "SLOT_BLOCK_CREATED";
    public static final String BLOCK_CANCELLED = "SLOT_BLOCK_CANCELLED";
    public static final String RATE_UPDATED = "RATE_UPDATED";
    public static final String FACILITY_SETTINGS_UPDATED = "FACILITY_SETTINGS_UPDATED";

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public AuditService(AuditLogRepository auditLogRepository, UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    /** Records an action performed by a logged-in user (actor type USER). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUserAction(Long actorUserId, String action, String entityType, Long entityId,
                                 Map<String, Object> details) {
        auditLogRepository.save(AuditLog.byUser(userRepository.getReferenceById(actorUserId),
                action, entityType, entityId, details));
    }

    /**
     * Adds {@code field: {old, new}} to an audit details map when the value
     * changed. Values are stored as strings so the JSON is stable.
     */
    public static void diff(Map<String, Object> changes, String field, Object oldValue, Object newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("old", oldValue == null ? null : oldValue.toString());
            change.put("new", newValue == null ? null : newValue.toString());
            changes.put(field, change);
        }
    }
}
