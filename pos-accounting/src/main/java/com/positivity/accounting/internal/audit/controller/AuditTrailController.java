package com.positivity.accounting.internal.audit.controller;

import com.positivity.accounting.internal.audit.dto.*;
import com.positivity.accounting.internal.audit.entity.ExceptionType;
import com.positivity.accounting.internal.exception.AuditTrailAuthorizationException;
import com.positivity.accounting.service.AuditTrailQueryService;
import com.positivity.accounting.service.AuditTrailService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for audit trail operations.
 */
@RestController
@RequestMapping("/v1/accounting/audit")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Audit Trail", description = "Audit trail operations for tracking accounting exceptions and overrides")
public class AuditTrailController {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";
    private static final String AUTHORIZATION_DENIED_CODE = "AUTHORIZATION_DENIED";

    private final AuditTrailService auditService;
    private final AuditTrailQueryService queryService;
    private final Clock clock;

    /**
     * Record a price override.
     */
    @Operation(
            operationId = "recordPriceOverrideAudit",
            summary = "Record Price Override Audit Entry",
            description = """
                    Records an audit trail entry for a price override exception, validating the acting \
                    role's authorization level against the override amount before persisting.
                    Use this tool when an order line price is overridden at the point of sale; do not use \
                    recordRefundAudit or recordCancellationAudit, which cover those other exception types.
                    Preconditions: the actor's role must be authorized for the override delta; a denied \
                    authorization publishes an AuthorizationDenied event and records nothing.
                    Required inputs: orderId (UUID), lineItemId (UUID), originalPrice, adjustedPrice, \
                    actorRole and reason; categoryCode is optional.
                    Emits an ACCOUNTING_AUDIT_PRICE_OVERRIDE event.
                    Returns 403 AUTHORIZATION_DENIED when the role's limit does not cover the override, and \
                    400 when required fields are missing.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "201",
                        description = "Price override recorded successfully",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = AuditTrailResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
                @ApiResponse(
                        responseCode = "403",
                        description = "Authorization denied - insufficient privileges for override amount"),
                @ApiResponse(responseCode = "422", description = "Policy validation failed"),
                @ApiResponse(responseCode = "500", description = "Internal server error")
            })
    @PostMapping("/price-override")
    @EmitEvent(id = "ACCOUNTING_AUDIT_PRICE_OVERRIDE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:submit"})
    @PreAuthorize("hasAuthority('accounting:events:submit')")
    public ResponseEntity<Object> recordPriceOverride(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Price override exception with the acting role and audit reason.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Manager price override", value = """
                                                                    {"orderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "lineItemId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "originalPrice":49.99,
                                                                     "adjustedPrice":39.99,
                                                                     "actorRole":"STORE_MANAGER",
                                                                     "reason":"Price match against competitor"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PriceOverrideRequest request,
            HttpServletRequest httpRequest) {
        try {
            AuditTrailResponse response = auditService.recordPriceOverride(request);
            return ResponseEntity.status(HttpStatus.CREATED).body((Object) response);
        } catch (AuditTrailAuthorizationException e) {
            log.warn("Price override authorization denied: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(buildErrorResponse(
                            AUTHORIZATION_DENIED_CODE, e.getMessage(), HttpStatus.FORBIDDEN, httpRequest));
        } catch (Exception e) {
            log.error("Error recording price override", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildErrorResponse(
                            INTERNAL_ERROR_CODE,
                            "Failed to record price override",
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            httpRequest));
        }
    }

    /**
     * Record a refund.
     */
    @Operation(operationId = "recordRefundAudit", summary = "Record Refund Audit Entry", description = """
                    Records an audit trail entry for a refund exception, validating refund policy and \
                    settlement handling for the original payment before persisting.
                    Use this tool when a customer refund is granted; do not use refundCustomerCredit, which \
                    actually moves money out of a standing credit, and do not use recordPriceOverrideAudit \
                    for price changes.
                    Preconditions: the refund must pass the refund authorization policy for its type and \
                    amount; a denial records nothing.
                    Required inputs: invoiceId (UUID), paymentId (UUID), refundType, refundAmount and \
                    originalPaymentStatus; actorId is optional.
                    Emits an ACCOUNTING_AUDIT_REFUND event.
                    Returns 403 AUTHORIZATION_DENIED when separate authorization is required and absent, \
                    and 400 when required fields are missing.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "201",
                        description = "Refund recorded successfully",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = AuditTrailResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
                @ApiResponse(
                        responseCode = "403",
                        description = "Authorization denied - separate authorization required"),
                @ApiResponse(responseCode = "422", description = "Refund policy validation failed"),
                @ApiResponse(responseCode = "500", description = "Internal server error")
            })
    @PostMapping("/refund")
    @EmitEvent(id = "ACCOUNTING_AUDIT_REFUND", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:submit"})
    @PreAuthorize("hasAuthority('accounting:events:submit')")
    public ResponseEntity<Object> recordRefund(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Refund exception details with type, amount and original payment status.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Settled payment reversal", value = """
                                                                    {"invoiceId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "paymentId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "refundType":"REVERSAL",
                                                                     "refundAmount":25.00,
                                                                     "originalPaymentStatus":"SETTLED"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    RefundRequest request,
            HttpServletRequest httpRequest) {
        try {
            AuditTrailResponse response = auditService.recordRefund(request);
            return ResponseEntity.status(HttpStatus.CREATED).body((Object) response);
        } catch (AuditTrailAuthorizationException e) {
            log.warn("Refund authorization denied: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(buildErrorResponse(
                            AUTHORIZATION_DENIED_CODE, e.getMessage(), HttpStatus.FORBIDDEN, httpRequest));
        } catch (Exception e) {
            log.error("Error recording refund", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildErrorResponse(
                            INTERNAL_ERROR_CODE,
                            "Failed to record refund",
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            httpRequest));
        }
    }

    /**
     * Record a cancellation.
     */
    @Operation(operationId = "recordCancellationAudit", summary = "Record Cancellation Audit Entry", description = """
                    Records an audit trail entry for an order or invoice cancellation, capturing before and \
                    after document snapshots.
                    Use this tool when an order or invoice is cancelled upstream; do not use \
                    recordRefundAudit, which covers money returned on a retained document.
                    Preconditions: none enforced beyond validation; either orderId or invoiceId should \
                    identify the cancelled document.
                    Required inputs: cancellationType (ORDER_CANCELLED, INVOICE_CANCELLED or \
                    PAYMENT_FAILED), beforeSnapshot and afterSnapshot (JSON strings), actorRole and reason; \
                    orderId, invoiceId, actorId and partialPaymentInfo are optional.
                    Emits an ACCOUNTING_AUDIT_CANCELLATION event.
                    Returns 400 when required fields are missing.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "201",
                        description = "Cancellation recorded successfully",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = AuditTrailResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
                @ApiResponse(responseCode = "404", description = "Source document not found"),
                @ApiResponse(responseCode = "500", description = "Internal server error")
            })
    @PostMapping("/cancellation")
    @EmitEvent(id = "ACCOUNTING_AUDIT_CANCELLATION", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:submit"})
    @PreAuthorize("hasAuthority('accounting:events:submit')")
    public ResponseEntity<Object> recordCancellation(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Cancellation exception with before and after document snapshots.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Order cancellation", value = """
                                                                    {"orderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "cancellationType":"ORDER_CANCELLED",
                                                                     "beforeSnapshot":"{\\"status\\":\\"OPEN\\"}",
                                                                     "afterSnapshot":"{\\"status\\":\\"CANCELLED\\"}",
                                                                     "actorRole":"SERVICE_ADVISOR",
                                                                     "reason":"Customer cancelled before work began"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CancellationRequest request,
            HttpServletRequest httpRequest) {
        try {
            AuditTrailResponse response = auditService.recordCancellation(request);
            return ResponseEntity.status(HttpStatus.CREATED).body((Object) response);
        } catch (Exception e) {
            log.error("Error recording cancellation", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildErrorResponse(
                            INTERNAL_ERROR_CODE,
                            "Failed to record cancellation",
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            httpRequest));
        }
    }

    /**
     * Get audit entries for an order.
     */
    @Operation(operationId = "getAuditTrailByOrder", summary = "Get Audit Trail For Order", description = """
                    Returns all audit trail entries recorded against one order, covering price overrides and \
                    cancellations.
                    Use this tool when the order id is known; use getAuditTrailByType or \
                    getAuditTrailByDateRange instead for cross-document review.
                    Preconditions: none; an order with no exceptions yields an empty list.
                    Required inputs: orderId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty list when no audit entries exist for the order.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Audit entries retrieved successfully",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = AuditTrailResponse.class))),
                @ApiResponse(responseCode = "404", description = "Order not found"),
                @ApiResponse(responseCode = "500", description = "Internal server error")
            })
    @GetMapping({"/order/{orderId}", "/by-order/{orderId}"})
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:view"})
    @PreAuthorize("hasAuthority('accounting:events:view')")
    public ResponseEntity<List<AuditTrailResponse>> getByOrderId(
            @Parameter(description = "Order ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID orderId) {
        List<AuditTrailResponse> entries = queryService.getByOrderId(orderId);
        return ResponseEntity.ok(entries);
    }

    /**
     * Get audit entries for an invoice.
     */
    @Operation(operationId = "getAuditTrailByInvoice", summary = "Get Audit Trail For Invoice", description = """
                    Returns all audit trail entries recorded against one invoice, covering refunds and \
                    cancellations.
                    Use this tool when the invoice id is known; use getAuditTrailByOrder for order-scoped \
                    entries instead.
                    Preconditions: none; an invoice with no exceptions yields an empty list.
                    Required inputs: invoiceId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty list when no audit entries exist for the invoice.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Audit entries retrieved successfully",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = AuditTrailResponse.class))),
                @ApiResponse(responseCode = "404", description = "Invoice not found"),
                @ApiResponse(responseCode = "500", description = "Internal server error")
            })
    @GetMapping("/invoice/{invoiceId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:view"})
    @PreAuthorize("hasAuthority('accounting:events:view')")
    public ResponseEntity<List<AuditTrailResponse>> getByInvoiceId(
            @Parameter(description = "Invoice ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    UUID invoiceId) {
        List<AuditTrailResponse> entries = queryService.getByInvoiceId(invoiceId);
        return ResponseEntity.ok(entries);
    }

    /**
     * Get audit entries by exception type and date range.
     */
    @Operation(operationId = "getAuditTrailByType", summary = "Get Audit Trail By Exception Type", description = """
                    Returns audit trail entries of one exception type (PRICE_OVERRIDE, REFUND or \
                    CANCELLATION) within a date range.
                    Use this tool to review one exception category across documents; use \
                    getAuditTrailByActor instead when reviewing one user's activity.
                    Preconditions: none; an empty range yields an empty list.
                    Required inputs: type as a path parameter plus startDate and endDate (ISO-8601 instants) \
                    as query parameters.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 when the type or dates cannot be parsed.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Audit entries retrieved successfully",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = AuditTrailResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid date range or exception type"),
                @ApiResponse(responseCode = "500", description = "Internal server error")
            })
    @GetMapping("/type/{type}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:view"})
    @PreAuthorize("hasAuthority('accounting:events:view')")
    public ResponseEntity<List<AuditTrailResponse>> getByType(
            @Parameter(description = "Exception type", required = true) @PathVariable ExceptionType type,
            @Parameter(description = "Start date in ISO 8601 format", required = true, example = "2026-01-01T00:00:00Z")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant startDate,
            @Parameter(description = "End date in ISO 8601 format", required = true, example = "2026-01-28T23:59:59Z")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant endDate) {
        List<AuditTrailResponse> entries = queryService.getByTypeAndDateRange(type, startDate, endDate);
        return ResponseEntity.ok(entries);
    }

    /**
     * Get audit entries by actor and date range.
     */
    @Operation(operationId = "getAuditTrailByActor", summary = "Get Audit Trail By Actor", description = """
                    Returns audit trail entries recorded by one actor (user) within a date range, across all \
                    exception types.
                    Use this tool to review a specific user's overrides, refunds and cancellations; use \
                    getAuditTrailByType instead to slice by exception category.
                    Preconditions: none; an unknown actor yields an empty list.
                    Required inputs: actorId (user identifier string) as a path parameter plus startDate and \
                    endDate (ISO-8601 instants) as query parameters.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 when the dates cannot be parsed.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Audit entries retrieved successfully",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = AuditTrailResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid date range or actor ID"),
                @ApiResponse(responseCode = "404", description = "Actor not found"),
                @ApiResponse(responseCode = "500", description = "Internal server error")
            })
    @GetMapping("/actor/{actorId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:view"})
    @PreAuthorize("hasAuthority('accounting:events:view')")
    public ResponseEntity<List<AuditTrailResponse>> getByActor(
            @Parameter(description = "Actor (User) ID", required = true, example = "person-12345") @PathVariable
                    String actorId,
            @Parameter(description = "Start date in ISO 8601 format", required = true, example = "2026-01-01T00:00:00Z")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant startDate,
            @Parameter(description = "End date in ISO 8601 format", required = true, example = "2026-01-28T23:59:59Z")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant endDate) {
        List<AuditTrailResponse> entries = queryService.getByActorAndDateRange(actorId, startDate, endDate);
        return ResponseEntity.ok(entries);
    }

    /**
     * Get audit entries by date range.
     */
    @Operation(operationId = "getAuditTrailByDateRange", summary = "Get Audit Trail By Date Range", description = """
                    Returns all audit trail entries of every exception type within a date range.
                    Use this tool for a broad period review; use getAuditTrailByType or \
                    getAuditTrailByActor instead when a narrower slice is wanted.
                    Preconditions: none; a quiet range yields an empty list.
                    Required inputs: startDate and endDate (ISO-8601 instants) as query parameters.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 when the dates cannot be parsed.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Audit entries retrieved successfully",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = AuditTrailResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid date range"),
                @ApiResponse(responseCode = "500", description = "Internal server error")
            })
    @GetMapping("/range")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:view"})
    @PreAuthorize("hasAuthority('accounting:events:view')")
    public ResponseEntity<List<AuditTrailResponse>> getByDateRange(
            @Parameter(description = "Start date in ISO 8601 format", required = true, example = "2026-01-01T00:00:00Z")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant startDate,
            @Parameter(description = "End date in ISO 8601 format", required = true, example = "2026-01-28T23:59:59Z")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant endDate) {
        List<AuditTrailResponse> entries = queryService.getByDateRange(startDate, endDate);
        return ResponseEntity.ok(entries);
    }

    private ApiError buildErrorResponse(String code, String message, HttpStatus status, HttpServletRequest request) {
        String correlationId = request.getHeader(X_CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        return ApiError.of(code, message, status.value(), Instant.now(clock).toString(), correlationId);
    }
}
