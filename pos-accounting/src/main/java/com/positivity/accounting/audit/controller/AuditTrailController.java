package com.positivity.accounting.audit.controller;

import com.positivity.accounting.audit.dto.*;
import com.positivity.accounting.audit.entity.ExceptionType;
import com.positivity.accounting.audit.service.AuditTrailQueryService;
import com.positivity.accounting.audit.service.AuditTrailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for audit trail operations.
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@Slf4j
public class AuditTrailController {
    
    private final AuditTrailService auditService;
    private final AuditTrailQueryService queryService;
    
    /**
     * Record a price override.
     */
    @PostMapping("/price-override")
    public ResponseEntity<?> recordPriceOverride(@Valid @RequestBody PriceOverrideRequest request) {
        try {
            AuditTrailResponse response = auditService.recordPriceOverride(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (AuditTrailService.AuthorizationException e) {
            log.warn("Price override authorization denied: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error recording price override", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to record price override"));
        }
    }
    
    /**
     * Record a refund.
     */
    @PostMapping("/refund")
    public ResponseEntity<?> recordRefund(@Valid @RequestBody RefundRequest request) {
        try {
            AuditTrailResponse response = auditService.recordRefund(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (AuditTrailService.AuthorizationException e) {
            log.warn("Refund authorization denied: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error recording refund", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to record refund"));
        }
    }
    
    /**
     * Record a cancellation.
     */
    @PostMapping("/cancellation")
    public ResponseEntity<?> recordCancellation(@Valid @RequestBody CancellationRequest request) {
        try {
            AuditTrailResponse response = auditService.recordCancellation(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            log.error("Error recording cancellation", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to record cancellation"));
        }
    }
    
    /**
     * Get audit entries for an order.
     */
    @GetMapping("/order/{orderId}")
    public ResponseEntity<List<AuditTrailResponse>> getByOrderId(@PathVariable UUID orderId) {
        List<AuditTrailResponse> entries = queryService.getByOrderId(orderId);
        return ResponseEntity.ok(entries);
    }
    
    /**
     * Get audit entries for an invoice.
     */
    @GetMapping("/invoice/{invoiceId}")
    public ResponseEntity<List<AuditTrailResponse>> getByInvoiceId(@PathVariable UUID invoiceId) {
        List<AuditTrailResponse> entries = queryService.getByInvoiceId(invoiceId);
        return ResponseEntity.ok(entries);
    }
    
    /**
     * Get audit entries by exception type and date range.
     */
    @GetMapping("/type/{type}")
    public ResponseEntity<List<AuditTrailResponse>> getByType(
            @PathVariable ExceptionType type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate) {
        List<AuditTrailResponse> entries = queryService.getByTypeAndDateRange(type, startDate, endDate);
        return ResponseEntity.ok(entries);
    }
    
    /**
     * Get audit entries by actor and date range.
     */
    @GetMapping("/actor/{actorId}")
    public ResponseEntity<List<AuditTrailResponse>> getByActor(
            @PathVariable UUID actorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate) {
        List<AuditTrailResponse> entries = queryService.getByActorAndDateRange(actorId, startDate, endDate);
        return ResponseEntity.ok(entries);
    }
    
    /**
     * Get audit entries by date range.
     */
    @GetMapping("/range")
    public ResponseEntity<List<AuditTrailResponse>> getByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate) {
        List<AuditTrailResponse> entries = queryService.getByDateRange(startDate, endDate);
        return ResponseEntity.ok(entries);
    }
    
    private static class Map {
        public static java.util.Map<String, String> of(String key, String value) {
            return java.util.Collections.singletonMap(key, value);
        }
    }
}
