package com.positivity.invoice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.RefundPaymentResponse;
import com.positivity.invoice.internal.enums.RefundReason;
import com.positivity.invoice.internal.enums.VoidReason;
import com.positivity.invoice.service.PaymentReversalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
        paymentReversalService.voidPayment(invoiceId, paymentId, request.reason(), request.notes());
        return ResponseEntity.ok().build();
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
        var saved = paymentReversalService.refundPayment(
                invoiceId, paymentId, request.amount(), request.reason(), request.notes());

        RefundPaymentResponse response = new RefundPaymentResponse();
        response.setRefundId(saved.getRefundId());
        response.setInvoiceId(saved.getInvoiceId());
        response.setPaymentIntentId(saved.getPaymentIntentId());
        response.setAmount(saved.getAmount());
        response.setReason(saved.getReason());
        response.setNotes(saved.getNotes());
        response.setStatus(saved.getStatus());
        response.setGatewayReference(saved.getGatewayReference());
        response.setCompletedAt(saved.getCompletedAt());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private record VoidPaymentRequest(@NotNull VoidReason reason, String notes) {}

    private record RefundPaymentRequest(
            @NotNull @Positive BigDecimal amount, @NotNull RefundReason reason, String notes) {}
}
