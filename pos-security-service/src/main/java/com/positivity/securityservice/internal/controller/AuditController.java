package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.AuditEventCreatedResponse;
import com.positivity.securityservice.internal.dto.AuditEventSearchFilter;
import com.positivity.securityservice.internal.dto.AuditLogEventDto;
import com.positivity.securityservice.internal.dto.AuditLogEventRequest;
import com.positivity.securityservice.internal.dto.PricingSnapshotCreatedResponse;
import com.positivity.securityservice.internal.dto.PricingSnapshotDto;
import com.positivity.securityservice.internal.dto.PricingSnapshotRequest;
import com.positivity.securityservice.service.AuditEventService;
import com.positivity.securityservice.service.PricingSnapshotService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit and pricing snapshot API enforcing immutable write-once semantics.
 *
 * Issue: #41
 */
@RestController
@RequestMapping("/v1/audit")
@RequiredArgsConstructor
@Tag(name = "Audit", description = "Audit event and pricing snapshot endpoints with immutable write-once behavior")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class AuditController {

    private final AuditEventService auditEventService;
    private final PricingSnapshotService pricingSnapshotService;

    @EmitEvent(id = "SECURITY_AUDIT_EVENT_CREATE", apiVersion = "1")
    @PostMapping("/events")
    @PreAuthorize("hasAuthority('security:audit:create')")
    @Operation(operationId = "createAuditEvent", summary = "Record an Immutable Audit Event", description = """
                    Records an immutable audit event and returns the generated event id and server timestamp.
                    Use this tool to persist a write-once audit fact; do not use createPricingSnapshot, which \
                    records a pricing rule trace, and note that updates and deletes of audit events are rejected \
                    with 405 by design.
                    Preconditions: the caller must hold security:audit:create; the actor is resolved server-side \
                    from the security context, so any actorId in the body is ignored.
                    Required inputs: eventType, entityId, entityType, oldValue, and newValue (empty strings are \
                    accepted, null is not); context is optional and stored as serialized JSON.
                    Emits a SECURITY_AUDIT_EVENT_CREATE event; the stored record can never be modified or deleted.
                    Returns 400 when any required field is missing or a value cannot be serialized to JSON.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Audit event created",
            content = @Content(schema = @Schema(implementation = AuditEventCreatedResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid audit event payload",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Insufficient authority",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AuditEventCreatedResponse> createEvent(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The audit fact to record: what changed, on which entity, from and to what.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Role change event", value = """
                                                                    {"eventType":"ROLE_ASSIGNED",
                                                                     "entityId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "entityType":"USER",
                                                                     "oldValue":"",
                                                                     "newValue":"SHOP_MGR",
                                                                     "context":{"reason":"onboarding"}}
                                                                    """)))
                    @RequestBody
                    @NonNull
                    AuditLogEventRequest request) {
        AuditLogEventDto created = auditEventService.createEvent(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AuditEventCreatedResponse.builder()
                        .eventId(created.getEventId())
                        .timestamp(created.getTimestamp())
                        .build());
    }

    @GetMapping("/events/{eventId}")
    @PreAuthorize("hasAuthority('security:audit:view')")
    @Operation(operationId = "getAuditEvent", summary = "Get One Audit Event by Id", description = """
                    Returns a previously recorded audit event by its event id.
                    Use this tool when the event id is known; use searchAuditEvents instead to filter by time \
                    window, actor, event type, or aggregate.
                    Preconditions: the caller must hold security:audit:view and the event must exist.
                    Required inputs: eventId (UUID) as a path parameter.
                    No events are emitted and no state changes; audit records are immutable.
                    Returns 404 when no audit event exists for the supplied id.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Audit event returned successfully",
            content = @Content(schema = @Schema(implementation = AuditLogEventDto.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Insufficient authority",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Audit event not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AuditLogEventDto> getEvent(@PathVariable @NonNull UUID eventId) {
        return ResponseEntity.ok(auditEventService.getEvent(eventId));
    }

    @GetMapping("/events")
    @PreAuthorize("hasAuthority('security:audit:view')")
    @Operation(operationId = "searchAuditEvents", summary = "Search Audit Events With Filters", description = """
                    Searches audit events with pagination, filtering by time window, actor, event type, and \
                    aggregate identifier.
                    Use this tool to query the audit trail; use getAuditEvent instead when the event id is known, \
                    and requestAuditExport for bulk extraction as a file.
                    Preconditions: the caller must hold security:audit:view; when both bounds are given, fromDate \
                    must be strictly before toDate (fromDate inclusive, toDate exclusive).
                    Required inputs: none are mandatory; fromDate, toDate, actorId, eventType, and aggregateId are \
                    applied as filters, while workorderId, movementId, productId, sku, correlationId, reasonCode, \
                    pageToken, and locationIds are accepted for contract compatibility but not yet applied.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 when fromDate is not before toDate or a parameter fails type conversion.
                    """)
    @ApiResponse(responseCode = "200", description = "Audit events returned successfully")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid filter criteria",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Insufficient authority",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Page<AuditLogEventDto>> searchAuditEvents(
            @Parameter(description = "Inclusive start timestamp (ISO-8601)", example = "2026-01-01T00:00:00Z")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant fromDate,
            @Parameter(description = "Exclusive end timestamp (ISO-8601)", example = "2026-12-31T23:59:59Z")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant toDate,
            @Parameter(description = "Actor username or user identifier", example = "advisor.jane")
                    @RequestParam(required = false)
                    String actorId,
            @Parameter(description = "Workorder UUID - one word per workspace naming policy")
                    @RequestParam(required = false)
                    UUID workorderId,
            @Parameter(description = "Movement UUID", example = "77777777-7777-7777-7777-777777777777")
                    @RequestParam(required = false)
                    UUID movementId,
            @Parameter(description = "Product UUID", example = "88888888-8888-8888-8888-888888888888")
                    @RequestParam(required = false)
                    UUID productId,
            @Parameter(description = "SKU code", example = "BRAKE-PAD-001") @RequestParam(required = false) String sku,
            @Parameter(description = "Event type code", example = "PERMISSION_DENIED") @RequestParam(required = false)
                    String eventType,
            @Parameter(description = "Aggregate identifier", example = "role:manager") @RequestParam(required = false)
                    String aggregateId,
            @Parameter(
                            description = "Correlation ID UUID for distributed tracing",
                            example = "99999999-9999-9999-9999-999999999999")
                    @RequestParam(required = false)
                    UUID correlationId,
            @Parameter(description = "Reason code string", example = "MANUAL_REVIEW") @RequestParam(required = false)
                    String reasonCode,
            @Parameter(description = "Cursor token for page navigation", example = "eyJwYWdlIjoyfQ")
                    @RequestParam(required = false)
                    String pageToken,
            @Parameter(
                            description = "Location UUID list - repeated query param",
                            example = "11111111-1111-1111-1111-111111111111")
                    @RequestParam(required = false)
                    List<String> locationIds,
            @Parameter(hidden = true) Pageable pageable) {

        AuditEventSearchFilter filter = AuditEventSearchFilter.builder()
                .fromDate(fromDate)
                .toDate(toDate)
                .actorId(actorId)
                .workorderId(workorderId)
                .movementId(movementId)
                .productId(productId)
                .sku(sku)
                .eventType(eventType)
                .aggregateId(aggregateId)
                .correlationId(correlationId)
                .reasonCode(reasonCode)
                .pageToken(pageToken)
                .locationIds(locationIds)
                .build();

        return ResponseEntity.ok(auditEventService.searchEventsFiltered(filter, pageable));
    }

    @DeleteMapping("/events/**")
    @PreAuthorize("hasAuthority('security:audit:create')")
    @Operation(operationId = "rejectAuditEventDelete", summary = "Reject Audit Event Deletion", description = """
                    Rejects every attempt to delete audit events, unconditionally answering 405 Method Not Allowed.
                    Use this tool never; audit events are write-once, so use createAuditEvent to record facts and \
                    searchAuditEvents to read them instead.
                    Preconditions: none that permit success; the operation fails by design for any path under the \
                    audit events resource.
                    Required inputs: none are honored; the request is rejected regardless of path or payload.
                    No events are emitted and no state changes; the endpoint exists only to make immutability \
                    explicit.
                    Returns 405 in all cases.
                    """)
    @ApiResponse(responseCode = "405", description = "Method not allowed for immutable audit events")
    public ResponseEntity<Void> deleteNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @PutMapping("/events/**")
    @PreAuthorize("hasAuthority('security:audit:create')")
    @Operation(operationId = "rejectAuditEventUpdate", summary = "Reject Audit Event Modification", description = """
                    Rejects every attempt to modify audit events, unconditionally answering 405 Method Not Allowed.
                    Use this tool never; audit events are write-once, so record a new fact with createAuditEvent \
                    instead of editing an existing one.
                    Preconditions: none that permit success; the operation fails by design for any path under the \
                    audit events resource.
                    Required inputs: none are honored; the request is rejected regardless of path or payload.
                    No events are emitted and no state changes; the endpoint exists only to make immutability \
                    explicit.
                    Returns 405 in all cases.
                    """)
    @ApiResponse(responseCode = "405", description = "Method not allowed for immutable audit events")
    public ResponseEntity<Void> updateNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @EmitEvent(id = "SECURITY_AUDIT_PRICING_SNAPSHOT_CREATE", apiVersion = "1")
    @PostMapping("/pricing-snapshots")
    @PreAuthorize("hasAuthority('security:audit:create')")
    @Operation(
            operationId = "createPricingSnapshot",
            summary = "Record an Immutable Pricing Snapshot",
            description = """
                    Records an immutable pricing snapshot with its ordered rule-evaluation trace and returns the \
                    generated snapshot id.
                    Use this tool to preserve how a price was computed for later audit; do not use \
                    createAuditEvent, which records generic entity-change events without a rule trace.
                    Preconditions: the caller must hold security:audit:create; the snapshot is write-once and \
                    cannot be modified afterwards.
                    Required inputs: quoteContext, finalPrice, and evaluationSteps, where every step needs ruleId, \
                    status, inputs, and outputs; evaluationSteps may be an empty list but not null.
                    Emits a SECURITY_AUDIT_PRICING_SNAPSHOT_CREATE event.
                    Returns 400 when quoteContext, finalPrice, evaluationSteps, or any per-step field is missing, \
                    or when a JSON field cannot be serialized.
                    """)
    @ApiResponse(responseCode = "201", description = "Pricing snapshot created")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid pricing snapshot payload",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PricingSnapshotCreatedResponse> createPricingSnapshot(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The evaluated quote context, final price, and rule trace to preserve.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Snapshot with one rule step", value = """
                                                                    {"quoteContext":{"sku":"BRAKE-PAD-001","quantity":2},
                                                                     "finalPrice":129.99,
                                                                     "evaluationSteps":[
                                                                       {"ruleId":"MSRP_BASE",
                                                                        "status":"APPLIED",
                                                                        "inputs":{"listPrice":149.99},
                                                                        "outputs":{"price":129.99}}]}
                                                                    """)))
                    @RequestBody
                    @NonNull
                    PricingSnapshotRequest request) {
        PricingSnapshotDto created = pricingSnapshotService.createSnapshot(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PricingSnapshotCreatedResponse.builder()
                        .snapshotId(created.getSnapshotId())
                        .build());
    }

    @GetMapping("/pricing-snapshots/{snapshotId}")
    @PreAuthorize("hasAuthority('security:audit:view')")
    @Operation(operationId = "getPricingSnapshot", summary = "Get One Pricing Snapshot by Id", description = """
                    Returns an immutable pricing snapshot with its rule-evaluation steps ordered by rule id.
                    Use this tool when the snapshot id is known; use searchAuditEvents instead for general audit \
                    queries, since snapshots have no search endpoint.
                    Preconditions: the caller must hold security:audit:view and the snapshot must exist.
                    Required inputs: snapshotId (UUID) as a path parameter.
                    No events are emitted and no state changes; snapshots are read-only after creation.
                    Returns 400 with INVALID_REQUEST, not 404, when no snapshot exists for the supplied id; callers \
                    must treat that 400 as a miss.
                    """)
    @ApiResponse(responseCode = "200", description = "Pricing snapshot returned successfully")
    @ApiResponse(
            responseCode = "404",
            description = "Pricing snapshot not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PricingSnapshotDto> getPricingSnapshot(@PathVariable @NonNull UUID snapshotId) {
        return ResponseEntity.ok(pricingSnapshotService.getSnapshot(snapshotId));
    }
}
