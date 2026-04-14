package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.AuditLogEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for immutable audit log events.
 *
 * Issue: #41
 */
@Repository
public interface AuditLogEventRepository extends JpaRepository<AuditLogEvent, UUID> {

    List<AuditLogEvent> findByEventTypeOrderByTimestampDesc(String eventType);

    List<AuditLogEvent> findByEntityIdAndEntityTypeOrderByTimestampDesc(String entityId, String entityType);

    List<AuditLogEvent> findByEntityIdAndEntityTypeAndTimestampBetweenOrderByTimestampDesc(
            String entityId, String entityType, Instant from, Instant to);
}
