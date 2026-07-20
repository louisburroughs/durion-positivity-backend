package com.positivity.invoice.internal.controller;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.InvoiceRefundResponse;
import com.positivity.invoice.internal.dto.RefundPaymentResponse;
import com.positivity.invoice.internal.enums.RefundReason;
import com.positivity.invoice.internal.enums.VoidReason;
import com.positivity.invoice.internal.observability.BusinessSpanSupport;
import com.positivity.invoice.service.PaymentReversalService;
import com.positivity.invoice.service.RefundPaymentResult;
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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/invoices")
@PreAuthorize("isAuthenticated()")
@Tag(name = "PaymentReversal")
public class PaymentReversalController {

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("pos-invoice");
    private static final String DOMAIN = "invoicing";
    private static final String TEAM = "invoice-eng";

    private final PaymentReversalService paymentReversalService;

    public PaymentReversalController(@NonNull PaymentReversalService paymentReversalService) {
        this.paymentReversalService = paymentReversalService;
    }

    @PostMapping("/{invoiceId}/payments/{paymentId}/void")
    @EmitEvent(id = "INVOICE_PAYMENT_VOID", apiVersion = "1")
    @Operation(
            summary = "Void authorized payment",
            description = "Void a previously authorized invoice payment before it is captured")
    @ApiResponse(responseCode = "200", description = "Payment voided")
    @ApiResponse(responseCode = "404", description = "Payment intent not found")
    @ApiResponse(responseCode = "409", description = "Invalid payment state")
    @ApiResponse(responseCode = "422", description = "Void window expired")
    public ResponseEntity<Void> voidPayment(
            @PathVariable @NonNull UUID invoiceId,
            @PathVariable @NonNull UUID paymentId,
            @Valid @RequestBody @NonNull VoidPaymentRequest request) {
        Span span = TRACER.spanBuilder("Void Invoice Payment").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Void Invoice Payment");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            paymentReversalService.voidPayment(invoiceId, paymentId, request.reason(), request.notes());
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @PostMapping("/{invoiceId}/payments/{paymentId}/refunds")
    @EmitEvent(id = "INVOICE_PAYMENT_REFUND", apiVersion = "1")
    @Operation(
            summary = "Refund captured payment",
            description = "Create a refund for a captured invoice payment and record the refund details")
    @ApiResponse(responseCode = "201", description = "Refund created")
    @ApiResponse(responseCode = "404", description = "Payment intent not found")
    @ApiResponse(responseCode = "409", description = "Invalid payment state")
    @ApiResponse(responseCode = "422", description = "Refund window expired or insufficient refundable amount")
    public ResponseEntity<RefundPaymentResponse> refundPayment(
            @PathVariable @NonNull UUID invoiceId,
            @PathVariable @NonNull UUID paymentId,
            @Valid @RequestBody @NonNull RefundPaymentRequest request) {
        Span span = TRACER.spanBuilder("Refund Payment").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Refund Payment");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            var saved = paymentReversalService.refundPayment(
                    invoiceId,
                    paymentId,
                    request.amount(),
                    request.reason(),
                    request.notes(),
                    request.externalReference());

            RefundPaymentResponse response = new RefundPaymentResponse();
            response.setRefundId(saved.getRefundId());
            response.setInvoiceId(saved.getInvoiceId());
            response.setPaymentIntentId(saved.getPaymentIntentId());
            response.setAmount(saved.getAmount());
            response.setReason(saved.getReason());
            response.setNotes(saved.getNotes());
            response.setStatus(saved.getStatus());
            response.setGatewayReference(saved.getGatewayReference());
            response.setExternalReference(saved.getExternalReference());
            response.setCompletedAt(saved.getCompletedAt());

            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @GetMapping("/{invoiceId}/refunds")
    @PreAuthorize("hasAuthority('invoice:manage')")
    @EmitEvent(id = "INVOICE_REFUND_LIST", apiVersion = "1")
    @Operation(
            summary = "List refunds for an invoice",
            description = "Return every refund record anchored to the invoice — payment-intent refunds whose"
                    + " intent belongs to the invoice and standalone invoice-anchored refunds alike —"
                    + " for warranty settlement reconciliation")
    @ApiResponse(responseCode = "200", description = "Refund records returned")
    @ApiResponse(responseCode = "404", description = "Invoice not found")
    public List<InvoiceRefundResponse> listRefunds(@PathVariable @NonNull UUID invoiceId) {
        return paymentReversalService.listRefundsForInvoice(invoiceId).stream()
                .map(PaymentReversalController::toRefundListEntry)
                .toList();
    }

    @NonNull
    private static InvoiceRefundResponse toRefundListEntry(@NonNull RefundPaymentResult result) {
        InvoiceRefundResponse entry = new InvoiceRefundResponse();
        entry.setId(result.getRefundId());
        entry.setPaymentIntentId(result.getPaymentIntentId());
        entry.setAmount(result.getAmount());
        entry.setStatus(result.getStatus());
        entry.setReason(result.getReason());
        entry.setExternalReference(result.getExternalReference());
        entry.setGatewayReference(result.getGatewayReference());
        entry.setRequestedAt(result.getRequestedAt());
        entry.setCompletedAt(result.getCompletedAt());
        return entry;
    }

    @Schema(description = "Request to void an authorized payment before capture")
    private record VoidPaymentRequest(
            @NotNull
            @Schema(
                    description = "Reason the payment is being voided",
                    example = "CUSTOMER_REQUEST",
                    requiredMode = REQUIRED)
            VoidReason reason,

            @Schema(
                    description = "Optional free-text notes explaining the void",
                    example = "Customer cancelled before pickup",
                    requiredMode = NOT_REQUIRED)
            String notes) {}

    @Schema(description = "Request to refund a captured payment")
    private record RefundPaymentRequest(
            @NotNull
            @Positive
            @Schema(
                    description = "Amount to refund from the captured payment",
                    example = "25.00",
                    requiredMode = REQUIRED)
            BigDecimal amount,

            @NotNull
            @Schema(
                    description = "Reason the payment is being refunded",
                    example = "CUSTOMER_RETURN",
                    requiredMode = REQUIRED)
            RefundReason reason,

            @Schema(
                    description = "Optional free-text notes explaining the refund",
                    example = "Returned damaged part",
                    requiredMode = NOT_REQUIRED)
            String notes,

            @Size(max = 64)
            @Schema(
                    description = "Optional correlation id to an external record (e.g. a warranty claim settlement)",
                    example = "WC-2026-000042",
                    requiredMode = NOT_REQUIRED)
            String externalReference) {}
}
