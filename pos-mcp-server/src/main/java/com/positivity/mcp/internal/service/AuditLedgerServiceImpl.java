package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.dto.AuditEventResponse;
import com.positivity.mcp.internal.dto.AuditQuery;
import com.positivity.mcp.internal.entity.NltiAuditEvent;
import com.positivity.mcp.service.AuditLedgerService;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class AuditLedgerServiceImpl implements AuditLedgerService {

    @Override
    public void append(@NonNull NltiAuditEvent event) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public Page<AuditEventResponse> query(@NonNull AuditQuery query, @NonNull Pageable pageable) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
