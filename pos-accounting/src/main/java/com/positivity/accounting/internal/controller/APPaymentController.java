package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.APPaymentResponse;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.dto.VendorBillSummaryResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.APPaymentService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
import org.springdoc.core.annotations.ParameterObject;
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

    private final APPaymentService apPaymentService;

    @PostMapping("/payments")
    @EmitEvent(id = "AP_PAYMENT_EXECUTE", apiVersion = "1")
    @Operation(
            operationId = "executeApPayment",
            summary = "Execute Vendor Payment",
            description = """
                    Executes an AP vendor payment through the payment gateway, optionally allocating it \
                    across approved vendor bills, and posts the corresponding GL entries.
                    Use this tool to pay a vendor; do not use applyPayment, which is the AR-side application \
                    of customer payments to invoices, and use listApBills first to find APPROVED bills to \
                    allocate against.
                    Preconditions: every allocated bill must exist, be APPROVED and belong to the vendor, \
                    and the allocation total must not exceed the gross amount.
                    Required inputs: vendorId (UUID), grossAmount (min 0.01), currency (3-char ISO code), \
                    paymentRef (max 100 chars, the idempotency key) and paymentMethod (e.g. ACH, CHECK); \
                    feeAmount, netAmount, paymentSource, memo and explicit allocations are optional.
                    Emits an AP_PAYMENT_EXECUTE event; the call is idempotent on paymentRef, replaying the \
                    same ref with the same payload as a 200 instead of paying twice.
                    Returns 200 on an idempotent replay, 409 IDEMPOTENCY_CONFLICT when the paymentRef exists \
                    with a different payload, 400 when a bill is missing, unapproved or over-allocated, and \
                    500 PAYMENT_GATEWAY_FAILURE when the gateway cannot be reached.
                    """,
            tags = {"AP Payments"})
    @ApiResponse(responseCode = "200", description = "Idempotent replay: existing payment returned")
    @ApiResponse(responseCode = "201", description = "Payment executed successfully (new payment created)")
    @ApiResponse(responseCode = "400", description = "Validation error: negative amounts, invalid bills, etc.")
    @ApiResponse(responseCode = "409", description = "Conflict: paymentRef exists with different payload")
    @ApiResponse(responseCode = "500", description = "Payment gateway failure")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:pay"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_PAY + "')")
    public @NonNull ResponseEntity<APPaymentResponse> executePayment(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Vendor payment instruction with idempotency key and optional bill allocations.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "ACH payment allocated to one bill",
                                                            value = """
                                                                    {"vendorId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "grossAmount":250.00,
                                                                     "currency":"USD",
                                                                     "paymentRef":"ap-pay-2026-08-13-007",
                                                                     "paymentMethod":"ACH",
                                                                     "allocations":[
                                                                       {"vendorBillId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                        "appliedAmount":250.00}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    ExecuteAPPaymentRequest request,
            Authentication authentication) {

        String currentUser = authentication != null ? authentication.getName() : "system";
        log.info(
                "Executing payment for vendor(mask) {} with paymentRef(mask) {}",
                maskForLog(request.getVendorId()),
                maskForLog(request.getPaymentRef()));

        // Check if payment already exists for idempotency
        Optional<APPaymentResponse> existing = apPaymentService.getPaymentByRef(request.getPaymentRef());
        if (existing.isPresent()) {
            // Idempotent replay: validate and return existing payment with 200 OK
            log.info("Idempotent replay for paymentRef(mask) {}", maskForLog(request.getPaymentRef()));
            APPaymentResponse response = apPaymentService.executePayment(request, currentUser);
            return ResponseEntity.ok(response);
        }

        // New payment: return 201 Created
        APPaymentResponse response = apPaymentService.executePayment(request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/payments/{paymentId}")
    @Operation(
            operationId = "getApPayment",
            summary = "Get AP Payment Details",
            description = """
                    Returns one AP payment with its bill allocations and GL posting status.
                    Use this tool when the payment id is already known; use getApPaymentByRef instead when \
                    only the idempotency reference is available.
                    Preconditions: the payment must exist.
                    Required inputs: paymentId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no AP payment exists for the supplied id.
                    """,
            tags = {"AP Payments"})
    @ApiResponse(responseCode = "200", description = "Payment found")
    @ApiResponse(responseCode = "404", description = "Payment not found")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_VIEW + "')")
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
            operationId = "getApPaymentByRef",
            summary = "Get AP Payment By Reference",
            description = """
                    Returns one AP payment looked up by its paymentRef, the caller-chosen idempotency key \
                    supplied at execution time.
                    Use this tool to check whether a payment reference was already executed before retrying \
                    executeApPayment; use getApPayment instead when the payment UUID is known.
                    Preconditions: a payment must have been executed with this paymentRef.
                    Required inputs: paymentRef (1-100 chars, no newlines) as a path parameter; there is no \
                    request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no AP payment exists for the supplied reference.
                    """,
            tags = {"AP Payments"})
    @ApiResponse(responseCode = "200", description = "Payment found")
    @ApiResponse(responseCode = "404", description = "Payment not found")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_VIEW + "')")
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
            operationId = "listApBills",
            summary = "List Eligible Vendor Bills",
            description = """
                    Lists vendor bills eligible for payment, meaning those in APPROVED status, ordered by due \
                    date oldest first with nulls last, then bill date, then bill id.
                    Use this tool to pick bills before calling executeApPayment; do not use \
                    listVendorBills on the vendor-bill API, which returns bills of every status.
                    Preconditions: none; the sort order is server-controlled and cannot be overridden.
                    Required inputs: none; vendorId (UUID) is an optional filter and page size defaults \
                    to 20.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 when the vendor id is malformed.
                    """,
            tags = {"AP Payments"})
    @ApiResponse(responseCode = "200", description = "Bills retrieved successfully")
    @ApiResponse(responseCode = "400", description = "Invalid vendor ID")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_VIEW + "')")
    public @NonNull ResponseEntity<Page<VendorBillSummaryResponse>> listBills(
            @RequestParam(required = false)
                    @Parameter(
                            description = "Vendor UUID",
                            example = "01936e5b-4567-7a3d-8b6e-1a2345678901",
                            required = false)
                    @Nullable
                    UUID vendorId,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {

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
