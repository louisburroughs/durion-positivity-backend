package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.AuditEventCreatedResponse;
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
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
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
public class AuditController {

    private final AuditEventService auditEventService;
    private final PricingSnapshotService pricingSnapshotService;

    @EmitEvent(id = "SECURITY_AUDIT_EVENT_CREATE", apiVersion = "1")
    @PostMapping("/events")
    @PreAuthorize("hasAuthority('security:audit:create')")
    @Operation(
            summary = "Create audit event",
            description = "Creates an immutable audit event record and returns the generated event identifier.")
    @ApiResponse(responseCode = "201", description = "Audit event created")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid audit event payload",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AuditEventCreatedResponse> createEvent(@RequestBody @NonNull AuditLogEventRequest request) {
        AuditLogEventDto created = auditEventService.createEvent(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AuditEventCreatedResponse.builder()
                        .eventId(created.getEventId())
                        .timestamp(created.getTimestamp())
                        .build());
    }

    @GetMapping("/events/{eventId}")
    @PreAuthorize("hasAuthority('security:audit:view')")
    @Operation(
            summary = "Get audit event",
            description = "Returns a previously recorded audit event by its event identifier.")
    @ApiResponse(responseCode = "200", description = "Audit event returned successfully")
    @ApiResponse(
            responseCode = "404",
            description = "Audit event not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AuditLogEventDto> getEvent(@PathVariable @NonNull UUID eventId) {
        return ResponseEntity.ok(auditEventService.getEvent(eventId));
    }

    @GetMapping("/events")
    @PreAuthorize("hasAuthority('security:audit:view')")
    @Operation(
            summary = "Search audit events",
            description =
                    "Searches audit events by event type alone or by entity identity with an optional time range.")
    @ApiResponse(responseCode = "200", description = "Audit events returned successfully")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid audit search criteria",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<AuditLogEventDto>> searchEvents(
            @Parameter(description = "Entity identifier to match. Must be supplied together with entityType.")
                    @RequestParam(required = false)
                    String entityId,
            @Parameter(description = "Entity type to match. Must be supplied together with entityId.")
                    @RequestParam(required = false)
                    String entityType,
            @Parameter(description = "Event type code to search by when not searching by entity.")
                    @RequestParam(required = false)
                    String eventType,
            @Parameter(
                            description = "Inclusive start timestamp for the audit search window.",
                            example = "2026-01-01T00:00:00Z")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @Parameter(
                            description = "Inclusive end timestamp for the audit search window.",
                            example = "2026-01-31T23:59:59Z")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to) {
        if (eventType != null
                && !eventType.isBlank()
                && entityId == null
                && entityType == null
                && from == null
                && to == null) {
            return ResponseEntity.ok(auditEventService.searchByEventType(eventType));
        }

        if ((entityId == null) != (entityType == null)) {
            throw new IllegalArgumentException("entityId and entityType must be provided together");
        }

        if (entityId == null && entityType == null) {
            throw new IllegalArgumentException("Provide eventType or both entityId and entityType");
        }

        return ResponseEntity.ok(auditEventService.searchEvents(entityId, entityType, from, to));
    }

    @DeleteMapping("/events/**")
    @PreAuthorize("hasAuthority('security:audit:create')")
    @Operation(
            summary = "Delete audit events not allowed",
            description = "Audit events are immutable and cannot be deleted once recorded.")
    @ApiResponse(responseCode = "405", description = "Method not allowed for immutable audit events")
    public ResponseEntity<Void> deleteNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @PutMapping("/events/**")
    @PreAuthorize("hasAuthority('security:audit:create')")
    @Operation(
            summary = "Update audit events not allowed",
            description = "Audit events are immutable and cannot be modified after creation.")
    @ApiResponse(responseCode = "405", description = "Method not allowed for immutable audit events")
    public ResponseEntity<Void> updateNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @EmitEvent(id = "SECURITY_AUDIT_PRICING_SNAPSHOT_CREATE", apiVersion = "1")
    @PostMapping("/pricing-snapshots")
    @PreAuthorize("hasAuthority('security:audit:create')")
    @Operation(
            summary = "Create pricing snapshot",
            description = "Creates an immutable pricing snapshot record for later audit and traceability.")
    @ApiResponse(responseCode = "201", description = "Pricing snapshot created")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid pricing snapshot payload",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PricingSnapshotCreatedResponse> createPricingSnapshot(
            @RequestBody @NonNull PricingSnapshotRequest request) {
        PricingSnapshotDto created = pricingSnapshotService.createSnapshot(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PricingSnapshotCreatedResponse.builder()
                        .snapshotId(created.getSnapshotId())
                        .build());
    }

    @GetMapping("/pricing-snapshots/{snapshotId}")
    @PreAuthorize("hasAuthority('security:audit:view')")
    @Operation(
            summary = "Get pricing snapshot",
            description = "Returns an immutable pricing snapshot by its snapshot identifier.")
    @ApiResponse(responseCode = "200", description = "Pricing snapshot returned successfully")
    @ApiResponse(
            responseCode = "404",
            description = "Pricing snapshot not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PricingSnapshotDto> getPricingSnapshot(@PathVariable @NonNull UUID snapshotId) {
        return ResponseEntity.ok(pricingSnapshotService.getSnapshot(snapshotId));
    }
}
