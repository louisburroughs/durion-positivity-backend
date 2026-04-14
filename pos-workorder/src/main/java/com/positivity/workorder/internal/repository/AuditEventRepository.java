package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.AuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    List<AuditEvent> findByEntityTypeAndEntityIdOrderByEventTimestampDesc(String entityType, UUID entityId);
}
