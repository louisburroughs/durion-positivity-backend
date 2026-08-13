package com.positivity.invoice.internal.controller;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.RefundPaymentResponse;
import com.positivity.invoice.internal.enums.RefundReason;
import com.positivity.invoice.service.PaymentReversalService;
import com.positivity.invoice.service.RefundPaymentResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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

/**
 * Standalone refunds — refunds not anchored to a captured PaymentIntent (#926).
 *
 * <p>Covers cases where the original payment is not in the system: walk-in warranty claims on
 * sales from a predecessor system, pre-deploy invoices, vendor-paid scenarios. The refund is
 * anchored to the invoice when one exists, or directly to the customer party otherwise, and is
 * disbursed out of band (till, check, vendor payment). The service layer gates both endpoints
 * with the finance-only {@code ISSUE_MANUAL_REFUND} authority.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1")
@PreAuthorize("isAuthenticated()")
@Tag(name = "StandaloneRefund")
public class StandaloneRefundController {

    private final PaymentReversalService paymentReversalService;

    public StandaloneRefundController(@NonNull PaymentReversalService paymentReversalService) {
        this.paymentReversalService = paymentReversalService;
    }

    @PostMapping("/invoices/{invoiceId}/refunds")
    @EmitEvent(id = "INVOICE_STANDALONE_REFUND", apiVersion = "1")
    @Operation(
            operationId = "createStandaloneInvoiceRefund",
            summary = "Record Standalone Invoice Refund",
            description = """
                    Records a refund against an invoice whose original payment is not in this system — \
                    predecessor-system sales, pre-deploy invoices, vendor-paid warranty work — while the cash \
                    disbursement itself happens out of band (till, check, or vendor payment).
                    Use this tool only when no captured payment intent exists to refund; do not use refundPayment, \
                    which reverses a CAPTURED gateway payment, and use createStandalonePartyRefund instead when no \
                    invoice is on file at all.
                    Preconditions: the invoice must exist, the caller needs the finance-only ISSUE_MANUAL_REFUND \
                    authority, and cumulative refunds across payment-anchored and standalone records may not exceed \
                    the invoice total.
                    Required inputs: amount (positive) and reason (a RefundReason such as CUSTOMER_RETURN); notes \
                    and externalReference are optional, and a retry replaying the same externalReference returns \
                    the existing record instead of double-recording.
                    Emits an INVOICE_STANDALONE_REFUND event and stores a COMPLETED refund record anchored to the \
                    invoice; no gateway call is made.
                    Returns 201 with the refund record, 404 when the invoice does not exist, and 422 when the \
                    amount exceeds the invoice's remaining refundable balance.
                    """)
    @ApiResponse(responseCode = "201", description = "Refund recorded")
    @ApiResponse(responseCode = "404", description = "Invoice not found")
    @ApiResponse(responseCode = "422", description = "Insufficient refundable amount")
    public ResponseEntity<RefundPaymentResponse> refundInvoiceStandalone(
            @PathVariable @NonNull UUID invoiceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Out-of-band refund to record against the invoice.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Walk-in warranty refund", value = """
                                                                    {"amount":25.00,"reason":"CUSTOMER_RETURN",
                                                                     "notes":"Walk-in warranty claim on a predecessor-system cash sale",
                                                                     "externalReference":"WC-2026-000042"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    StandaloneRefundRequest request) {
        RefundPaymentResult saved = paymentReversalService.refundInvoiceStandalone(
                invoiceId, request.amount(), request.reason(), request.notes(), request.externalReference());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @PostMapping("/refunds")
    @EmitEvent(id = "INVOICE_PARTY_STANDALONE_REFUND", apiVersion = "1")
    @Operation(
            operationId = "createStandalonePartyRefund",
            summary = "Record Standalone Party Refund",
            description = """
                    Records a refund anchored directly to a customer party when no invoice exists in this system, \
                    such as a vendor-paid warranty settlement on a predecessor-system sale; the cash disbursement \
                    itself happens out of band.
                    Use this tool only when there is no invoice to anchor to; use createStandaloneInvoiceRefund \
                    instead when an invoice is on file, and refundPayment when the captured payment itself exists.
                    Preconditions: the caller needs the finance-only ISSUE_MANUAL_REFUND authority; because there is \
                    no invoice, no refundable-amount cap applies.
                    Required inputs: partyId (non-blank, max 64 characters), amount (positive) and reason (a \
                    RefundReason); notes and externalReference are optional, and a retry replaying the same \
                    externalReference among the party's purely party-anchored records returns the existing record.
                    Emits an INVOICE_PARTY_STANDALONE_REFUND event and stores a COMPLETED refund record anchored to \
                    the party; no gateway call is made.
                    Returns 201 with the refund record, and 400 when partyId is blank.
                    """)
    @ApiResponse(responseCode = "201", description = "Refund recorded")
    @ApiResponse(responseCode = "400", description = "Missing party anchor")
    public ResponseEntity<RefundPaymentResponse> refundPartyStandalone(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Out-of-band refund to record against a customer party with no invoice on file.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Vendor-paid settlement", value = """
                                                                    {"partyId":"party-000123","amount":25.00,
                                                                     "reason":"CUSTOMER_RETURN",
                                                                     "notes":"Vendor-paid warranty settlement, no invoice on file",
                                                                     "externalReference":"WC-2026-000042"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    PartyStandaloneRefundRequest request) {
        RefundPaymentResult saved = paymentReversalService.refundPartyStandalone(
                request.partyId(), request.amount(), request.reason(), request.notes(), request.externalReference());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @NonNull
    private static RefundPaymentResponse toResponse(@NonNull RefundPaymentResult saved) {
        RefundPaymentResponse response = new RefundPaymentResponse();
        response.setRefundId(saved.getRefundId());
        response.setInvoiceId(saved.getInvoiceId());
        response.setPaymentIntentId(saved.getPaymentIntentId());
        response.setPartyId(saved.getPartyId());
        response.setAmount(saved.getAmount());
        response.setReason(saved.getReason());
        response.setNotes(saved.getNotes());
        response.setStatus(saved.getStatus());
        response.setGatewayReference(saved.getGatewayReference());
        response.setExternalReference(saved.getExternalReference());
        response.setCompletedAt(saved.getCompletedAt());
        return response;
    }

    @Schema(description = "Request to record a standalone refund against an invoice")
    private record StandaloneRefundRequest(
            @NotNull @Positive @Schema(description = "Amount to refund", example = "25.00", requiredMode = REQUIRED)
            BigDecimal amount,

            @NotNull
            @Schema(
                    description = "Reason the refund is being issued",
                    example = "CUSTOMER_RETURN",
                    requiredMode = REQUIRED)
            RefundReason reason,

            @Schema(
                    description = "Optional free-text notes explaining the refund",
                    example = "Walk-in warranty claim on a predecessor-system cash sale",
                    requiredMode = NOT_REQUIRED)
            String notes,

            @Size(max = 64)
            @Schema(
                    description = "Optional correlation id to an external record (e.g. a warranty claim settlement)",
                    example = "WC-2026-000042",
                    requiredMode = NOT_REQUIRED)
            String externalReference) {}

    @Schema(description = "Request to record a standalone refund anchored to a customer party")
    private record PartyStandaloneRefundRequest(
            @NotBlank
            @Size(max = 64)
            @Schema(
                    description = "Customer party the refund is anchored to",
                    example = "party-000123",
                    requiredMode = REQUIRED)
            String partyId,

            @NotNull @Positive @Schema(description = "Amount to refund", example = "25.00", requiredMode = REQUIRED)
            BigDecimal amount,

            @NotNull
            @Schema(
                    description = "Reason the refund is being issued",
                    example = "CUSTOMER_RETURN",
                    requiredMode = REQUIRED)
            RefundReason reason,

            @Schema(
                    description = "Optional free-text notes explaining the refund",
                    example = "Vendor-paid warranty settlement, no invoice on file",
                    requiredMode = NOT_REQUIRED)
            String notes,

            @Size(max = 64)
            @Schema(
                    description = "Optional correlation id to an external record (e.g. a warranty claim settlement)",
                    example = "WC-2026-000042",
                    requiredMode = NOT_REQUIRED)
            String externalReference) {}
}
