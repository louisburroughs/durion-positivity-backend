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
public class AuditController {

    private final AuditEventService auditEventService;
    private final PricingSnapshotService pricingSnapshotService;

    @EmitEvent(id = "SECURITY_AUDIT_EVENT_CREATE", apiVersion = "1")
    @PostMapping("/events")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AuditEventCreatedResponse> createEvent(@RequestBody @NonNull AuditLogEventRequest request) {
        AuditLogEventDto created = auditEventService.createEvent(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AuditEventCreatedResponse.builder()
                        .eventId(created.getEventId())
                        .timestamp(created.getTimestamp())
                        .build());
    }

    @GetMapping("/events/{eventId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AuditLogEventDto> getEvent(@PathVariable @NonNull UUID eventId) {
        return ResponseEntity.ok(auditEventService.getEvent(eventId));
    }

    @GetMapping("/events")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<AuditLogEventDto>> searchEvents(
            @RequestParam String entityId,
            @RequestParam String entityType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(auditEventService.searchEvents(entityId, entityType, from, to));
    }

    @DeleteMapping("/events/**")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @PutMapping("/events/**")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> updateNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @EmitEvent(id = "SECURITY_AUDIT_PRICING_SNAPSHOT_CREATE", apiVersion = "1")
    @PostMapping("/pricing-snapshots")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PricingSnapshotCreatedResponse> createPricingSnapshot(
            @RequestBody @NonNull PricingSnapshotRequest request) {
        PricingSnapshotDto created = pricingSnapshotService.createSnapshot(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PricingSnapshotCreatedResponse.builder().snapshotId(created.getSnapshotId()).build());
    }

    @GetMapping("/pricing-snapshots/{snapshotId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PricingSnapshotDto> getPricingSnapshot(@PathVariable @NonNull UUID snapshotId) {
        return ResponseEntity.ok(pricingSnapshotService.getSnapshot(snapshotId));
    }
}
