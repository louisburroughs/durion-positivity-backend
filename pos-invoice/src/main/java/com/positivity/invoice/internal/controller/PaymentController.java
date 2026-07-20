package com.positivity.invoice.internal.controller;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.InitiatePaymentRequest;
import com.positivity.invoice.internal.dto.InitiatePaymentResponse;
import com.positivity.invoice.internal.observability.BusinessSpanSupport;
import com.positivity.invoice.service.PaymentService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for payment initiation and capture operations.
 *
 * <p>
 * Permission enforcement is delegated to the service layer.
 * This controller handles HTTP mapping and request/response shapes (ADR-0017).
 *
 * Story #9.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/invoices")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Payment", description = "Card payment initiation and capture endpoints")
public class PaymentController {

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("pos-invoice");
    private static final String DOMAIN = "invoicing";
    private static final String TEAM = "invoice-eng";

    private final PaymentService paymentService;

    public PaymentController(@NonNull PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Initiates a card payment (SALE_CAPTURE or AUTH_ONLY) against an invoice.
     *
     * @param invoiceId unique invoice identifier
     * @param request   payment request including flow, amount, token, idempotency
     *                  key
     * @return created payment intent (HTTP 201)
     */
    @PostMapping("/{invoiceId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @EmitEvent(id = "INVOICE_PAYMENT_INITIATE", apiVersion = "1")
    @Operation(
            summary = "Initiate card payment",
            description = "Initiate a SALE_CAPTURE or AUTH_ONLY card payment against an invoice")
    @ApiResponse(responseCode = "201", description = "Payment intent created")
    @ApiResponse(responseCode = "400", description = "Invalid or missing required fields")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    @ApiResponse(responseCode = "422", description = "Payment method declined")
    @ApiResponse(responseCode = "503", description = "Payment gateway unavailable")
    public InitiatePaymentResponse initiatePayment(
            @PathVariable @NonNull UUID invoiceId, @Valid @RequestBody @NonNull InitiatePaymentRequest request) {
        return paymentService.initiatePayment(invoiceId, request);
    }

    /**
     * Explicitly captures an authorized payment hold (AUTH_ONLY flow).
     *
     * @param invoiceId invoice identifier
     * @param paymentId payment intent identifier (from prior AUTH_ONLY)
     * @param body      capture amount (may be partial)
     * @return updated payment intent (HTTP 200)
     */
    @PostMapping("/{invoiceId}/payments/{paymentId}/capture")
    @EmitEvent(id = "INVOICE_PAYMENT_CAPTURE", apiVersion = "1")
    @Operation(
            summary = "Capture authorized payment hold",
            description = "Capture all or part of a previously authorized invoice payment hold")
    @ApiResponse(responseCode = "200", description = "Payment captured")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    @ApiResponse(responseCode = "404", description = "Payment intent not found")
    public InitiatePaymentResponse capturePayment(
            @PathVariable @NonNull UUID invoiceId,
            @PathVariable @NonNull UUID paymentId,
            @Valid @RequestBody @NonNull CaptureAmountRequest body) {
        Span span = TRACER.spanBuilder("Capture Payment").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Capture Payment");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            InitiatePaymentResponse response =
                    paymentService.capturePayment(invoiceId, paymentId, body.amount(), body.captureIdempotencyKey());
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            return response;
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Capture request body wrapping the capture amount.
     * Story #9, AC3.
     */
    @Schema(description = "Request to capture all or part of an authorized payment hold")
    private record CaptureAmountRequest(
            @NotNull
            @Positive
            @Schema(
                    description = "Amount to capture from the authorized hold",
                    example = "89.99",
                    requiredMode = REQUIRED)
            BigDecimal amount,

            @NotBlank
            @Schema(
                    description = "Idempotency key ensuring the capture is processed at most once",
                    example = "capture-550e8400-1",
                    requiredMode = REQUIRED)
            String captureIdempotencyKey) {}
}
