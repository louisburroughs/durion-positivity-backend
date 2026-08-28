package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.dto.AuditEventAppend;
import com.positivity.mcp.internal.dto.AuditEventResponse;
import com.positivity.mcp.internal.dto.AuditQuery;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AuditLedgerService {
    void append(@NonNull AuditEventAppend event);

    @NonNull
    Page<AuditEventResponse> query(@NonNull AuditQuery query, @NonNull Pageable pageable);
}
