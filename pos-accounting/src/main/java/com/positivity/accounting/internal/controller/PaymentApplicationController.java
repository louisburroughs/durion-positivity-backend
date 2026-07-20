package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.dto.PaymentApplicationReversalRequest;
import com.positivity.accounting.internal.observability.BusinessSpanSupport;
import com.positivity.accounting.service.PaymentApplicationService;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for payment application operations (AR).
 *
 * Endpoints:
 * - POST /payments/{paymentId}/applications - Apply payment to invoices
 * - POST /payment-applications/{applicationId}/reverse - Reverse application
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114</a>
 */
@Slf4j
@RestController
@RequestMapping("/v1/accounting")
@RequiredArgsConstructor
@Tag(name = "Payment Applications", description = "Manage payment applications to invoices (AR)")
@Validated
public class PaymentApplicationController {

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("pos-accounting");
    private static final String DOMAIN = "accounting";
    private static final String TEAM = "accounting-eng";

    private final PaymentApplicationService paymentApplicationService;

    @PostMapping("/payments/{paymentId}/void")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:pay"})
    @PreAuthorize("hasAuthority('accounting:ap:pay')")
    @Operation(
            summary = "Void payment",
            description = "Void a payment before settlement.",
            tags = {"Payment Applications"})
    @ApiResponse(responseCode = "204", description = "Payment voided")
    @ApiResponse(responseCode = "404", description = "Payment not found")
    @ApiResponse(responseCode = "409", description = "Payment already applied; reverse applications first")
    @EmitEvent(id = "ACCOUNTING_PAYMENT_VOID", apiVersion = "1")
    public ResponseEntity<Void> voidPayment(
            @Parameter(description = "Payment identifier") @PathVariable UUID paymentId,
            @RequestBody(required = false) Object body) {
        Span span = TRACER.spanBuilder("Void Payment").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Void Payment");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            BusinessSpanSupport.logWithTraceContext(log, "Voiding payment(mask) {}", maskForLog(paymentId));
            paymentApplicationService.voidPayment(paymentId);
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @PostMapping("/payments/{paymentId}/reverse")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:pay"})
    @PreAuthorize("hasAuthority('accounting:ap:pay')")
    @Operation(
            summary = "Reverse payment",
            description = "Reverse a previously applied payment.",
            tags = {"Payment Applications"})
    @ApiResponse(responseCode = "204", description = "Payment applications reversed")
    @ApiResponse(responseCode = "404", description = "Payment not found")
    @ApiResponse(responseCode = "409", description = "Payment has no applications to reverse")
    @EmitEvent(id = "ACCOUNTING_PAYMENT_REVERSE", apiVersion = "1")
    public ResponseEntity<Void> reversePayment(
            @Parameter(description = "Payment identifier") @PathVariable UUID paymentId,
            @Valid @RequestBody PaymentApplicationReversalRequest request) {
        log.info(
                "Reversing payment(mask) {} with reason(mask): {}",
                maskForLog(paymentId),
                maskForLog(request.getReason()));
        paymentApplicationService.reversePayment(paymentId, request.getReason());
        return ResponseEntity.noContent().build();
    }

    /**
     * Apply a payment to one or more invoices.
     *
     * Business Rules (from Issue #114):
     * - Payment must be AVAILABLE with sufficient funds
     * - Each invoice must be applicable (not PaidInFull/Voided/Cancelled)
     * - Applications are atomic across all target invoices
     * - Idempotent via applicationRequestId
     * - Overpayments create CustomerCredit
     *
     * @param paymentId payment to apply
     * @param request   application request with invoices and amounts
     * @return application response with details
     */
    @PostMapping("/payments/{paymentId}/applications")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:payment:apply"})
    @PreAuthorize("hasAuthority('accounting:payment:apply')")
    @Operation(
            summary = "Apply payment",
            description = "Apply a payment to an invoice and update its status.",
            tags = {"Payment Applications"})
    @ApiResponse(responseCode = "201", description = "Payment applied successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request or insufficient funds")
    @ApiResponse(responseCode = "404", description = "Payment not found")
    @ApiResponse(responseCode = "409", description = "Currency mismatch or invoice not applicable")
    @EmitEvent(id = "ACCOUNTING_PAYMENT_APPLY", apiVersion = "1")
    public ResponseEntity<PaymentApplicationResponse> applyPayment(
            @Parameter(description = "Payment identifier") @PathVariable UUID paymentId,
            @Valid @RequestBody PaymentApplicationRequest request) {
        Span span = TRACER.spanBuilder("Apply Payment").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Apply Payment");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            log.info(
                    "Applying payment(mask) {} to {} invoices (request(mask): {})",
                    maskForLog(paymentId),
                    request.getApplications().size(),
                    maskForLog(request.getApplicationRequestId()));

            PaymentApplicationResponse response = paymentApplicationService.applyPaymentToInvoices(paymentId, request);

            log.info(
                    "Successfully applied payment(mask) {} with total {} to {} invoices",
                    maskForLog(paymentId),
                    response.getAppliedAmount(),
                    response.getApplications().size());

            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Reverse a payment application (compensating transaction).
     *
     * Business Rules (from Issue #114):
     * - Requires elevated permission (ACCOUNTING_ADMIN or AR_MANAGER)
     * - Requires non-empty reason for audit trail
     * - Reversals are NEW records, not deletions
     * - Restores invoice balance and payment unappliedAmount
     *
     * @param applicationId application to reverse
     * @param request       reversal request with reason
     * @return 204 No Content on success
     */
    @PostMapping("/payment-applications/{applicationId}/reverse")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:payment:reverse", "ACCOUNTING_ADMIN", "AR_MANAGER"})
    @PreAuthorize("hasAnyAuthority('accounting:payment:reverse', 'ACCOUNTING_ADMIN', 'AR_MANAGER')")
    @Operation(
            summary = "Reverse payment application",
            description = "Reverse a payment application with compensating transaction (no deletion).",
            tags = {"Payment Applications"})
    @ApiResponse(responseCode = "204", description = "Payment application reversed")
    @ApiResponse(responseCode = "400", description = "Invalid request or already reversed")
    @ApiResponse(responseCode = "404", description = "Payment application not found")
    @EmitEvent(id = "ACCOUNTING_PAYMENT_APPLICATION_REVERSE", apiVersion = "1")
    public ResponseEntity<Void> reversePaymentApplication(
            @Parameter(description = "Payment application identifier") @PathVariable UUID applicationId,
            @Valid @RequestBody PaymentApplicationReversalRequest request) {

        log.info(
                "Reversing payment application(mask) {} with reason(mask): {}",
                maskForLog(applicationId),
                maskForLog(request.getReason()));

        paymentApplicationService.reversePaymentApplication(applicationId, request.getReason());

        log.info("Successfully reversed payment application(mask) {}", maskForLog(applicationId));

        return ResponseEntity.noContent().build();
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
