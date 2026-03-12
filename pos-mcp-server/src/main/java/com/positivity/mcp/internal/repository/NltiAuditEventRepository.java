package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.entity.NltiAuditEvent;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NltiAuditEventRepository extends JpaRepository<NltiAuditEvent, UUID> {

    Page<NltiAuditEvent> findByCorrelationIdOrderByTimestampAsc(UUID correlationId, Pageable pageable);

    Page<NltiAuditEvent> findByTimestampBetweenOrderByTimestampAsc(
            OffsetDateTime from, OffsetDateTime to, Pageable pageable);
}
