package com.positivity.mcp.service;

import com.positivity.mcp.internal.dto.AuditEventResponse;
import com.positivity.mcp.internal.dto.AuditQuery;
import com.positivity.mcp.internal.entity.NltiAuditEvent;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AuditLedgerService {
    void append(@NonNull NltiAuditEvent event);

    @NonNull
    Page<AuditEventResponse> query(@NonNull AuditQuery query, @NonNull Pageable pageable);
}
