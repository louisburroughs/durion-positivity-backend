package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.dto.AuditEventResponse;
import com.positivity.mcp.internal.dto.AuditQuery;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.service.AuditLedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"nlti:audit:read"})
@RequestMapping("/v1/nlt/audit")
@Tag(name = "NLTI Audit", description = "NLTI Audit Ledger Query")
class AuditController {

    private final AuditLedgerService auditLedgerService;

    AuditController(@NonNull AuditLedgerService auditLedgerService) {
        this.auditLedgerService = auditLedgerService;
    }

    @GetMapping
    @EmitEvent(id = "NLTI_AUDIT_QUERY", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + McpPermissions.NLTI_AUDIT_READ + "')")
    @Operation(operationId = "searchNltiAuditEvents", summary = "Query the NLTI Audit Ledger", description = """
                    Returns a page of NLTI audit ledger entries recording request, intent, plan, confirmation and \
                    execution events.
                    Use this tool to trace what the NLTI pipeline did for a conversation or time window; filter by \
                    correlationId rather than paging the whole ledger when tracing a single request.
                    Preconditions: none; the ledger is append-only and entries exist only for activity that has \
                    already happened.
                    Required inputs: all filters are optional — correlationId (UUID) takes precedence, then from \
                    and to (ISO-8601 instants, applied only when both are present), then eventType (one of \
                    REQUEST, INTENT, PLAN, CONFIRMATION, EXECUTION_STEP, EXECUTION_COMPLETE, EXECUTION_FAILED); \
                    results default to page size 20 sorted by timestamp.
                    Emits a NLTI_AUDIT_QUERY event; the ledger itself is never modified by this call.
                    Returns 200 with a page of audit events, and 400 with code INVALID_EVENT_TYPE when eventType \
                    is not one of the supported values.
                    """)
    ResponseEntity<Page<AuditEventResponse>> queryAudit(
            @RequestParam(required = false) UUID correlationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String eventType,
            @ParameterObject @PageableDefault(size = 20, sort = "timestamp") Pageable pageable) {
        var query = new AuditQuery(correlationId, from, to, eventType);
        var page = auditLedgerService.query(query, pageable);
        return ResponseEntity.ok(page);
    }
}
