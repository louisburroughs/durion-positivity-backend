package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findByEntityTypeAndEntityIdOrderByEventTimestampDesc(String entityType, Long entityId);
}
