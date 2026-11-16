package com.parkit.repository;

import com.parkit.domain.entity.AuditLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for {@link com.parkit.domain.entity.AuditLog} (append-only). */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtAsc(String entityType, Long entityId);
}
