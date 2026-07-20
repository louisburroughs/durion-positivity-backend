package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.APPaymentResponse;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.dto.VendorBillSummaryResponse;
import com.positivity.accounting.internal.observability.BusinessSpanSupport;
import com.positivity.accounting.service.APPaymentService;
import com.positivity.events.EmitEvent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for AP (Accounts Payable) payment operations.
 *
 * <p>
 * Endpoints:
 * <ul>
 * <li>POST /v1/accounting/ap/payments - Execute vendor payment</li>
 * <li>GET /v1/accounting/ap/payments/{paymentId} - Get payment details</li>
 * <li>GET /v1/accounting/ap/bills - List eligible vendor bills</li>
 * </ul>
 *
 * @see APPaymentService
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/128">Issue
 *      #128</a>
 */
@Slf4j
@RestController
@RequestMapping("/v1/accounting/ap")
@Tag(name = "AP Payments", description = "Accounts Payable vendor payment operations")
@RequiredArgsConstructor
@Validated
public class APPaymentController {

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("pos-accounting");
    private static final String DOMAIN = "accounting";
    private static final String TEAM = "accounting-eng";

    private final APPaymentService apPaymentService;

    @PostMapping("/payments")
    @EmitEvent(id = "AP_PAYMENT_EXECUTE", apiVersion = "1")
    @Operation(
            summary = "Execute vendor payment",
            description = "Execute a vendor payment with optional explicit allocations to bills. "
                    + "Idempotent using paymentRef: same ref + same payload returns existing payment; "
                    + "same ref + different payload yields 409 conflict.",
            tags = {"AP Payments"})
    @ApiResponse(responseCode = "200", description = "Idempotent replay: existing payment returned")
    @ApiResponse(responseCode = "201", description = "Payment executed successfully (new payment created)")
    @ApiResponse(responseCode = "400", description = "Validation error: negative amounts, invalid bills, etc.")
    @ApiResponse(responseCode = "409", description = "Conflict: paymentRef exists with different payload")
    @ApiResponse(responseCode = "500", description = "Payment gateway failure")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:pay"})
    @PreAuthorize("hasAuthority('accounting:ap:pay')")
    public @NonNull ResponseEntity<APPaymentResponse> executePayment(
            @Valid @RequestBody @NonNull ExecuteAPPaymentRequest request, Authentication authentication) {
        Span span = TRACER.spanBuilder("Create Ap Payment").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Create Ap Payment");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            String currentUser = authentication != null ? authentication.getName() : "system";
            log.info(
                    "Executing payment for vendor(mask) {} with paymentRef(mask) {}",
                    maskForLog(request.getVendorId()),
                    maskForLog(request.getPaymentRef()));

            // Check if payment already exists for idempotency
            Optional<APPaymentResponse> existing = apPaymentService.getPaymentByRef(request.getPaymentRef());
            ResponseEntity<APPaymentResponse> result;
            if (existing.isPresent()) {
                // Idempotent replay: validate and return existing payment with 200 OK
                log.info("Idempotent replay for paymentRef(mask) {}", maskForLog(request.getPaymentRef()));
                APPaymentResponse response = apPaymentService.executePayment(request, currentUser);
                result = ResponseEntity.ok(response);
            } else {
                // New payment: return 201 Created
                APPaymentResponse response = apPaymentService.executePayment(request, currentUser);
                result = ResponseEntity.status(HttpStatus.CREATED).body(response);
            }
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            return result;
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @GetMapping("/payments/{paymentId}")
    @Operation(
            summary = "Get payment details",
            description = "Retrieve AP payment details including allocations and GL posting status.",
            tags = {"AP Payments"})
    @ApiResponse(responseCode = "200", description = "Payment found")
    @ApiResponse(responseCode = "404", description = "Payment not found")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:view"})
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    public @NonNull ResponseEntity<APPaymentResponse> getPayment(
            @PathVariable
                    @Parameter(description = "Payment UUID", example = "01936e5c-7890-7a3d-8b6e-2b3456789012")
                    @NonNull
                    UUID paymentId) {

        return apPaymentService
                .getPaymentById(paymentId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/payments/by-ref/{paymentRef}")
    @Operation(
            summary = "Get payment by reference",
            description = "Retrieve AP payment details by paymentRef (idempotency key).",
            tags = {"AP Payments"})
    @ApiResponse(responseCode = "200", description = "Payment found")
    @ApiResponse(responseCode = "404", description = "Payment not found")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:view"})
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    public @NonNull ResponseEntity<APPaymentResponse> getPaymentByRef(
            @PathVariable
                    @Parameter(
                            description = "Payment reference (idempotency key)",
                            example = "01936e5c-7890-7a3d-8b6e-2b3456789012")
                    @NotBlank
                    @Size(min = 1, max = 100, message = "Payment reference must be 1-100 characters")
                    @Pattern(regexp = "^[^\\r\\n]+$", message = "Payment reference must not contain newline characters")
                    @NonNull
                    String paymentRef) {

        return apPaymentService
                .getPaymentByRef(paymentRef)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/bills")
    @Operation(
            summary = "List eligible vendor bills",
            operationId = "listApBills",
            description =
                    "Get eligible vendor bills for payment (status = APPROVED). Bills are ordered by due date (oldest first, nulls last), then bill date, then bill ID. Sort order is server-controlled.",
            tags = {"AP Payments"})
    @ApiResponse(responseCode = "200", description = "Bills retrieved successfully")
    @ApiResponse(responseCode = "400", description = "Invalid vendor ID")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:view"})
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    public @NonNull ResponseEntity<Page<VendorBillSummaryResponse>> listBills(
            @RequestParam(required = false)
                    @Parameter(
                            description = "Vendor UUID",
                            example = "01936e5b-4567-7a3d-8b6e-1a2345678901",
                            required = false)
                    @Nullable
                    UUID vendorId,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<VendorBillSummaryResponse> bills = apPaymentService.listEligibleBills(vendorId, pageable);
        return ResponseEntity.ok(bills);
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }
}
