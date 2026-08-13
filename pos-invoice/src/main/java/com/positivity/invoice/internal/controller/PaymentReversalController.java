package com.positivity.invoice.internal.controller;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.InvoiceRefundResponse;
import com.positivity.invoice.internal.dto.RefundPaymentResponse;
import com.positivity.invoice.internal.enums.RefundReason;
import com.positivity.invoice.internal.enums.VoidReason;
import com.positivity.invoice.service.PaymentReversalService;
import com.positivity.invoice.service.RefundPaymentResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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

    private final PaymentReversalService paymentReversalService;

    public PaymentReversalController(@NonNull PaymentReversalService paymentReversalService) {
        this.paymentReversalService = paymentReversalService;
    }

    @PostMapping("/{invoiceId}/payments/{paymentId}/void")
    @EmitEvent(id = "INVOICE_PAYMENT_VOID", apiVersion = "1")
    @Operation(operationId = "voidPayment", summary = "Void Authorized Payment Hold", description = """
                    Voids a previously authorized invoice payment hold at the gateway before it is captured, \
                    releasing the customer's funds without any money movement.
                    Use this tool on an AUTHORIZED hold; do not use refundPayment, which returns funds from a \
                    payment that was already CAPTURED.
                    Preconditions: the payment intent must belong to the invoice and be AUTHORIZED, the caller needs \
                    the VOID_PAYMENT authority, and less than 24 hours may have elapsed since authorization unless \
                    the caller also holds SUPERVISOR_OVERRIDE.
                    Required inputs: reason (CUSTOMER_REQUEST, DUPLICATE_AUTHORIZATION, ENTRY_ERROR, \
                    FRAUD_PREVENTION, MANAGER_DISCRETION or OTHER); notes are optional free text.
                    Emits an INVOICE_PAYMENT_VOID event, moves the intent to VOIDED, and publishes a payment-voided \
                    notification.
                    Returns 200 with an empty body on success, 404 when the intent does not exist under the invoice, \
                    409 when the intent is not AUTHORIZED, 422 when the 24-hour void window has expired, and 500 \
                    when the gateway rejects the void.
                    """)
    @ApiResponse(responseCode = "200", description = "Payment voided")
    @ApiResponse(responseCode = "404", description = "Payment intent not found")
    @ApiResponse(responseCode = "409", description = "Invalid payment state")
    @ApiResponse(responseCode = "422", description = "Void window expired")
    public ResponseEntity<Void> voidPayment(
            @PathVariable @NonNull UUID invoiceId,
            @PathVariable @NonNull UUID paymentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Reason and optional notes explaining why the authorization is released.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Customer cancelled", value = """
                                                                    {"reason":"CUSTOMER_REQUEST",
                                                                     "notes":"Customer cancelled before pickup"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    VoidPaymentRequest request) {
        paymentReversalService.voidPayment(invoiceId, paymentId, request.reason(), request.notes());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{invoiceId}/payments/{paymentId}/refunds")
    @EmitEvent(id = "INVOICE_PAYMENT_REFUND", apiVersion = "1")
    @Operation(operationId = "refundPayment", summary = "Refund a Captured Payment", description = """
                    Refunds all or part of a CAPTURED invoice payment through the gateway and records the refund \
                    against the payment intent.
                    Use this tool when the original card payment lives in this system; do not use voidPayment, \
                    which releases an uncaptured hold, and use createStandaloneInvoiceRefund instead when the \
                    original payment is not on file.
                    Preconditions: the payment intent must belong to the invoice and be CAPTURED, the caller needs \
                    the REFUND_PAYMENT authority, less than 180 days may have elapsed since capture unless the \
                    caller holds SUPERVISOR_OVERRIDE, and cumulative refunds may not exceed the captured amount.
                    Required inputs: amount (positive) and reason (a RefundReason such as CUSTOMER_RETURN or \
                    SERVICE_ERROR); notes and externalReference are optional, and a retry replaying the same \
                    externalReference returns the existing refund instead of paying twice.
                    Emits an INVOICE_PAYMENT_REFUND event and publishes a payment-refunded notification on success; \
                    a gateway failure is persisted as a FAILED refund record, so callers must read the returned \
                    status rather than treating 201 as completed.
                    Returns 201 with the refund record, 404 when the intent does not exist under the invoice, 409 \
                    when the intent is not CAPTURED, and 422 when the 180-day window has expired or the amount \
                    exceeds the remaining refundable balance.
                    """)
    @ApiResponse(responseCode = "201", description = "Refund created")
    @ApiResponse(responseCode = "404", description = "Payment intent not found")
    @ApiResponse(responseCode = "409", description = "Invalid payment state")
    @ApiResponse(responseCode = "422", description = "Refund window expired or insufficient refundable amount")
    public ResponseEntity<RefundPaymentResponse> refundPayment(
            @PathVariable @NonNull UUID invoiceId,
            @PathVariable @NonNull UUID paymentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Refund amount, business reason and optional external correlation for the captured payment.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Partial return refund", value = """
                                                                    {"amount":25.00,"reason":"CUSTOMER_RETURN",
                                                                     "notes":"Returned damaged part",
                                                                     "externalReference":"WC-2026-000042"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    RefundPaymentRequest request) {
        var saved = paymentReversalService.refundPayment(
                invoiceId, paymentId, request.amount(), request.reason(), request.notes(), request.externalReference());

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

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{invoiceId}/refunds")
    @PreAuthorize("hasAuthority('invoice:manage')")
    @EmitEvent(id = "INVOICE_REFUND_LIST", apiVersion = "1")
    @Operation(operationId = "listInvoiceRefunds", summary = "List Refunds for an Invoice", description = """
                    Returns every refund record anchored to the invoice — refunds of captured payment intents and \
                    standalone invoice-anchored refunds alike — for warranty-settlement reconciliation.
                    Use this tool to reconcile what has already been returned before issuing another refund; do not \
                    use refundPayment or createStandaloneInvoiceRefund, which create refunds rather than list them.
                    Preconditions: the invoice must exist; the caller needs the invoice:manage authority.
                    Required inputs: invoiceId (UUID) as a path parameter; there is no request body or filtering.
                    Emits an INVOICE_REFUND_LIST audit event; no state changes — this is a read-only projection.
                    Returns 404 when no invoice exists for the supplied id.
                    """)
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
