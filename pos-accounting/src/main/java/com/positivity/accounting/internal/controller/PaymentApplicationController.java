package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.dto.PaymentApplicationReversalRequest;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.service.PaymentApplicationService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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

    private final PaymentApplicationService paymentApplicationService;

    @PostMapping("/payments/{paymentId}/void")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:pay"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_PAY + "')")
    @Operation(
            operationId = "voidPaymentApplication",
            summary = "Void Payment",
            description = """
                    Voids a receivable payment that has not been applied to any invoice, zeroing its \
                    unapplied amount so it can never be applied.
                    Use this tool for a payment recorded in error before any application; do not use \
                    reversePayment, which backs out a payment whose applications already exist.
                    Preconditions: the payment must exist and have no invoice applications; voiding an \
                    already-voided payment is a no-op.
                    Required inputs: paymentId (UUID) as a path parameter; the request body is optional and \
                    ignored.
                    Emits an ACCOUNTING_PAYMENT_VOID event.
                    Returns 404 when the payment is not found, 409 when invoice applications already exist \
                    (reverse those first), and 204 with no body on success.
                    """,
            tags = {"Payment Applications"})
    @ApiResponse(responseCode = "204", description = "Payment voided")
    @ApiResponse(responseCode = "404", description = "Payment not found")
    @ApiResponse(responseCode = "409", description = "Payment already applied; reverse applications first")
    @EmitEvent(id = "ACCOUNTING_PAYMENT_VOID", apiVersion = "1")
    public ResponseEntity<Void> voidPaymentApplication(
            @Parameter(description = "Payment identifier") @PathVariable UUID paymentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional and ignored; send an empty object or omit the body entirely.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Empty body", value = "{}")))
                    @RequestBody(required = false)
                    Object body) {
        log.info("Voiding payment(mask) {}", maskForLog(paymentId));
        paymentApplicationService.voidPayment(paymentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payments/{paymentId}/reverse")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:pay"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_PAY + "')")
    @Operation(
            operationId = "reversePayment",
            summary = "Reverse Payment",
            description = """
                    Reverses every application of a receivable payment as new compensating records, restoring \
                    each invoice's balance and the payment's unapplied amount.
                    Use this tool to back out a whole applied payment, which is also the required path for \
                    multi-invoice applications; do not use reversePaymentApplication, which reverses one \
                    single-invoice application, and do not use voidPaymentApplication, which only handles never-applied \
                    payments.
                    Preconditions: the payment must exist and have at least one application; \
                    already-reversed applications are skipped rather than double-reversed.
                    Required inputs: paymentId (UUID) as a path parameter and a reason of 10 to 1000 \
                    characters for the audit trail.
                    Emits an ACCOUNTING_PAYMENT_REVERSE event; reversals are new records, never deletions.
                    Returns 404 when the payment is not found, 409 when it has no applications to reverse, \
                    and 204 with no body on success.
                    """,
            tags = {"Payment Applications"})
    @ApiResponse(responseCode = "204", description = "Payment applications reversed")
    @ApiResponse(responseCode = "404", description = "Payment not found")
    @ApiResponse(responseCode = "409", description = "Payment has no applications to reverse")
    @EmitEvent(id = "ACCOUNTING_PAYMENT_REVERSE", apiVersion = "1")
    public ResponseEntity<Void> reversePayment(
            @Parameter(description = "Payment identifier") @PathVariable UUID paymentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Audit reason for reversing all of the payment's applications.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Reverse misapplied payment",
                                                            value =
                                                                    "{\"reason\":\"Payment applied to the wrong customer account\"}")))
                    @Valid
                    @RequestBody
                    PaymentApplicationReversalRequest request) {
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
     * - Optional allocationStrategy: CALLER_ORDER (default when absent) or
     *   OLDEST_FIRST (allocate by ascending invoice date) — Issue #955
     *
     * @param paymentId payment to apply
     * @param request   application request with invoices, amounts, and optional allocation strategy
     * @return application response with details
     */
    @PostMapping("/payments/{paymentId}/applications")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:payment:apply"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.PAYMENT_APPLY + "')")
    @Operation(
            operationId = "applyPayment",
            summary = "Apply Payment To Invoices",
            description = """
                    Applies a receivable payment to one or more invoices atomically, updating each invoice's \
                    balance and status and reducing the payment's unapplied amount.
                    Use this tool to settle invoices from an available payment; do not use \
                    applyCustomerCredit, which draws down a standing credit rather than a payment.
                    Preconditions: the payment must be AVAILABLE with sufficient unapplied funds, and every \
                    target invoice must be applicable (not paid in full, voided or cancelled); an overpayment \
                    creates a CustomerCredit for the excess.
                    Required inputs: paymentId (UUID) as a path parameter, applicationRequestId (max 100 \
                    chars, the idempotency key) and a non-empty applications list of invoiceId plus \
                    amountToApply (min 0.01); allocationStrategy is optional, CALLER_ORDER when omitted or \
                    OLDEST_FIRST to allocate by ascending invoice date.
                    Emits an ACCOUNTING_PAYMENT_APPLY event; the application is atomic across all invoices \
                    and idempotent on applicationRequestId.
                    Returns 404 when the payment is not found, 400 for insufficient funds, 409 for a \
                    currency mismatch or an inapplicable invoice, and 503 when the invoice service is \
                    unreachable.
                    """,
            tags = {"Payment Applications"})
    @ApiResponse(responseCode = "201", description = "Payment applied successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request or insufficient funds")
    @ApiResponse(responseCode = "404", description = "Payment not found")
    @ApiResponse(responseCode = "409", description = "Currency mismatch or invoice not applicable")
    @EmitEvent(id = "ACCOUNTING_PAYMENT_APPLY", apiVersion = "1")
    public ResponseEntity<PaymentApplicationResponse> applyPayment(
            @Parameter(description = "Payment identifier") @PathVariable UUID paymentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Idempotent, atomic allocation of the payment across one or more invoices.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Apply to two invoices oldest first",
                                                            value = """
                                                                    {"applicationRequestId":"apply-2026-08-13-042",
                                                                     "allocationStrategy":"OLDEST_FIRST",
                                                                     "applications":[
                                                                       {"invoiceId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                        "amountToApply":100.00},
                                                                       {"invoiceId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                        "amountToApply":50.00}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PaymentApplicationRequest request) {

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

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Reverse a payment application (compensating transaction).
     *
     * Business Rules (from Issue #114):
     * - Requires elevated permission (accounting:payment:reverse)
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
            scopes = {"accounting:payment:reverse"})
    @PreAuthorize("hasAnyAuthority('" + AccountingPermissions.PAYMENT_REVERSE + "')")
    @Operation(
            operationId = "reversePaymentApplication",
            summary = "Reverse Payment Application",
            description = """
                    Reverses one payment application as a new compensating record, restoring the invoice's \
                    balance and the payment's unapplied amount.
                    Use this tool for an application that settled a single invoice; do not use it on an \
                    application created by a multi-invoice apply request, which must be reversed as a whole \
                    via reversePayment.
                    Preconditions: the application must exist, must not already be reversed, and must not \
                    belong to a multi-application request.
                    Required inputs: applicationId (UUID) as a path parameter and a reason of 10 to 1000 \
                    characters for the audit trail; the caller needs accounting:payment:reverse authority.
                    Emits an ACCOUNTING_PAYMENT_APPLICATION_REVERSE event; the reversal is a new record, not \
                    a deletion.
                    Returns 404 when the application is not found, 409 when it is already reversed, 422 \
                    WHOLE_REQUEST_REVERSAL_REQUIRED when it belongs to a multi-application request, and 204 \
                    with no body on success.
                    """,
            tags = {"Payment Applications"})
    @ApiResponse(responseCode = "204", description = "Payment application reversed")
    @ApiResponse(responseCode = "400", description = "Invalid request or already reversed")
    @ApiResponse(responseCode = "404", description = "Payment application not found")
    @EmitEvent(id = "ACCOUNTING_PAYMENT_APPLICATION_REVERSE", apiVersion = "1")
    public ResponseEntity<Void> reversePaymentApplication(
            @Parameter(description = "Payment application identifier") @PathVariable UUID applicationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Audit reason for reversing this single payment application.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Reverse one application",
                                                            value =
                                                                    "{\"reason\":\"Applied to invoice with disputed line items\"}")))
                    @Valid
                    @RequestBody
                    PaymentApplicationReversalRequest request) {

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
