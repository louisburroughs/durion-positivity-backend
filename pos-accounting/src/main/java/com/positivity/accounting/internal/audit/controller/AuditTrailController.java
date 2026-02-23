package com.positivity.accounting.internal.audit.controller;

import com.positivity.accounting.internal.audit.dto.*;
import com.positivity.accounting.internal.audit.entity.ExceptionType;
import com.positivity.accounting.internal.exception.AuditTrailAuthorizationException;
import com.positivity.accounting.service.AuditTrailQueryService;
import com.positivity.accounting.service.AuditTrailService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for audit trail operations.
 */
@RestController
@RequestMapping("/v1/accounting/audit")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Audit Trail", description = "Audit trail operations for tracking accounting exceptions and overrides")
public class AuditTrailController {

    private final AuditTrailService auditService;
    private final AuditTrailQueryService queryService;

    /**
     * Record a price override.
     */
    @Operation(summary = "Record a price override", description = "Creates an audit trail entry for a price override exception with policy validation")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Price override recorded successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuditTrailResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
            @ApiResponse(responseCode = "403", description = "Authorization denied - insufficient privileges for override amount"),
            @ApiResponse(responseCode = "422", description = "Policy validation failed"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PostMapping("/price-override")
    @EmitEvent(id = "ACCOUNTING_AUDIT_PRICE_OVERRIDE", apiVersion = "1")
    @PreAuthorize("hasAuthority('accounting:events:submit')")
    public ResponseEntity<?> recordPriceOverride(@Valid @RequestBody PriceOverrideRequest request) {
        try {
            AuditTrailResponse response = auditService.recordPriceOverride(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (AuditTrailAuthorizationException e) {
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
    @Operation(summary = "Record a refund", description = "Creates an audit trail entry for a refund exception with policy validation and settlement handling")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Refund recorded successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuditTrailResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
            @ApiResponse(responseCode = "403", description = "Authorization denied - separate authorization required"),
            @ApiResponse(responseCode = "422", description = "Refund policy validation failed"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PostMapping("/refund")
    @EmitEvent(id = "ACCOUNTING_AUDIT_REFUND", apiVersion = "1")
    @PreAuthorize("hasAuthority('accounting:events:submit')")
    public ResponseEntity<?> recordRefund(@Valid @RequestBody RefundRequest request) {
        try {
            AuditTrailResponse response = auditService.recordRefund(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (AuditTrailAuthorizationException e) {
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
    @Operation(summary = "Record a cancellation", description = "Creates an audit trail entry for an order or invoice cancellation")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Cancellation recorded successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuditTrailResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
            @ApiResponse(responseCode = "404", description = "Source document not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PostMapping("/cancellation")
    @EmitEvent(id = "ACCOUNTING_AUDIT_CANCELLATION", apiVersion = "1")
    @PreAuthorize("hasAuthority('accounting:events:submit')")
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
    @Operation(summary = "Get audit trail for order", description = "Retrieves all audit trail entries associated with a specific order")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Audit entries retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuditTrailResponse.class))),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/order/{orderId}")
    @PreAuthorize("hasAuthority('accounting:events:view')")
    public ResponseEntity<List<AuditTrailResponse>> getByOrderId(
            @Parameter(description = "Order ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID orderId) {
        List<AuditTrailResponse> entries = queryService.getByOrderId(orderId);
        return ResponseEntity.ok(entries);
    }

    /**
     * Get audit entries for an invoice.
     */
    @Operation(summary = "Get audit trail for invoice", description = "Retrieves all audit trail entries associated with a specific invoice")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Audit entries retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuditTrailResponse.class))),
            @ApiResponse(responseCode = "404", description = "Invoice not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/invoice/{invoiceId}")
    @PreAuthorize("hasAuthority('accounting:events:view')")
    public ResponseEntity<List<AuditTrailResponse>> getByInvoiceId(
            @Parameter(description = "Invoice ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable UUID invoiceId) {
        List<AuditTrailResponse> entries = queryService.getByInvoiceId(invoiceId);
        return ResponseEntity.ok(entries);
    }

    /**
     * Get audit entries by exception type and date range.
     */
    @Operation(summary = "Get audit trail by exception type", description = "Retrieves audit trail entries filtered by exception type and date range")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Audit entries retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuditTrailResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid date range or exception type"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/type/{type}")
    @PreAuthorize("hasAuthority('accounting:events:view')")
    public ResponseEntity<List<AuditTrailResponse>> getByType(
            @Parameter(description = "Exception type", required = true) @PathVariable ExceptionType type,
            @Parameter(description = "Start date in ISO 8601 format", required = true, example = "2026-01-01T00:00:00Z") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @Parameter(description = "End date in ISO 8601 format", required = true, example = "2026-01-28T23:59:59Z") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate) {
        List<AuditTrailResponse> entries = queryService.getByTypeAndDateRange(type, startDate, endDate);
        return ResponseEntity.ok(entries);
    }

    /**
     * Get audit entries by actor and date range.
     */
    @Operation(summary = "Get audit trail by actor", description = "Retrieves audit trail entries for a specific actor (user) within a date range")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Audit entries retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuditTrailResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid date range or actor ID"),
            @ApiResponse(responseCode = "404", description = "Actor not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/actor/{actorId}")
    @PreAuthorize("hasAuthority('accounting:events:view')")
    public ResponseEntity<List<AuditTrailResponse>> getByActor(
            @Parameter(description = "Actor (User) ID", required = true, example = "person-12345") @PathVariable String actorId,
            @Parameter(description = "Start date in ISO 8601 format", required = true, example = "2026-01-01T00:00:00Z") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @Parameter(description = "End date in ISO 8601 format", required = true, example = "2026-01-28T23:59:59Z") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate) {
        List<AuditTrailResponse> entries = queryService.getByActorAndDateRange(actorId, startDate, endDate);
        return ResponseEntity.ok(entries);
    }

    /**
     * Get audit entries by date range.
     */
    @Operation(summary = "Get audit trail by date range", description = "Retrieves all audit trail entries within a specified date range")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Audit entries retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuditTrailResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid date range"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/range")
    @PreAuthorize("hasAuthority('accounting:events:view')")
    public ResponseEntity<List<AuditTrailResponse>> getByDateRange(
            @Parameter(description = "Start date in ISO 8601 format", required = true, example = "2026-01-01T00:00:00Z") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @Parameter(description = "End date in ISO 8601 format", required = true, example = "2026-01-28T23:59:59Z") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate) {
        List<AuditTrailResponse> entries = queryService.getByDateRange(startDate, endDate);
        return ResponseEntity.ok(entries);
    }

    private static class Map {
        public static java.util.Map<String, String> of(String key, String value) {
            return java.util.Collections.singletonMap(key, value);
        }
    }
}
