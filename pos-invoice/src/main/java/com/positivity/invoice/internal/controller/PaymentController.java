package com.positivity.invoice.internal.controller;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.InitiatePaymentRequest;
import com.positivity.invoice.internal.dto.InitiatePaymentResponse;
import com.positivity.invoice.internal.service.PaymentService;
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
    @Operation(operationId = "initiatePayment", summary = "Initiate Card Payment on Invoice", description = """
                    Initiates a card payment against an invoice through the payment gateway, creating a payment \
                    intent that is CAPTURED immediately (SALE_CAPTURE) or left AUTHORIZED as a hold (AUTH_ONLY).
                    Use this tool to take card tender; do not use capturePayment, which settles an existing \
                    AUTH_ONLY hold rather than starting a new payment.
                    Preconditions: the invoice must exist; the caller needs the PROCESS_PAYMENT authority, plus \
                    OVERRIDE_PAYMENT_LIMIT when the amount exceeds 500.00 and SELECT_PAYMENT_FLOW to choose \
                    AUTH_ONLY.
                    Required inputs: paymentFlow (SALE_CAPTURE or AUTH_ONLY), amount (positive), idempotencyKey and \
                    paymentToken (tokenised card reference, never a PAN); a replayed idempotencyKey with an \
                    identical payload returns the existing intent instead of charging twice.
                    Emits an INVOICE_PAYMENT_INITIATE event and records the gateway result on the intent.
                    Returns 201 with the intent, 404 when the invoice does not exist, 409 when the idempotencyKey \
                    was already used with a different payload, 422 when the gateway declines, and 403 when a \
                    required payment authority is missing.
                    """)
    @ApiResponse(responseCode = "201", description = "Payment intent created")
    @ApiResponse(responseCode = "400", description = "Invalid or missing required fields")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    @ApiResponse(responseCode = "422", description = "Payment method declined")
    @ApiResponse(responseCode = "503", description = "Payment gateway unavailable")
    public InitiatePaymentResponse initiatePayment(
            @PathVariable @NonNull UUID invoiceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Card tender details: flow, amount, idempotency key and tokenised card reference.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "One-step sale capture", value = """
                                                                    {"paymentFlow":"SALE_CAPTURE","amount":149.99,
                                                                     "idempotencyKey":"idem-018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a50",
                                                                     "paymentToken":"tok_1NXyZ2eZvKYlo2Cabc12345"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    InitiatePaymentRequest request) {
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
    @Operation(operationId = "capturePayment", summary = "Capture Authorized Payment Hold", description = """
                    Captures all or part of a previously authorized AUTH_ONLY payment hold at the gateway; when the \
                    captured amount is below the authorized amount, the remainder of the hold is voided.
                    Use this tool to settle an existing AUTHORIZED intent; do not use initiatePayment, which starts \
                    a new payment, and use voidPayment instead to release the hold without taking funds.
                    Preconditions: the payment intent must belong to the invoice and be in AUTHORIZED status; the \
                    caller needs the MANUAL_CAPTURE authority.
                    Required inputs: amount (positive, up to the authorized amount) and captureIdempotencyKey, which \
                    is forwarded to the gateway so a retried capture settles at most once.
                    Emits an INVOICE_PAYMENT_CAPTURE event; the intent moves to CAPTURED on success or \
                    CAPTURE_FAILED on decline, and an ambiguous gateway response is resolved by a status inquiry \
                    before failing.
                    Returns 200 with the captured intent, 404 when the intent does not exist under the invoice, 409 \
                    when the intent is not AUTHORIZED, 422 when the gateway declines the capture, and 403 when the \
                    MANUAL_CAPTURE authority is missing.
                    """)
    @ApiResponse(responseCode = "200", description = "Payment captured")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    @ApiResponse(responseCode = "404", description = "Payment intent not found")
    public InitiatePaymentResponse capturePayment(
            @PathVariable @NonNull UUID invoiceId,
            @PathVariable @NonNull UUID paymentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Amount to settle from the authorized hold and the capture idempotency key.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Partial capture", value = """
                                                                    {"amount":89.99,
                                                                     "captureIdempotencyKey":"capture-018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a51"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    CaptureAmountRequest body) {
        return paymentService.capturePayment(invoiceId, paymentId, body.amount(), body.captureIdempotencyKey());
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
