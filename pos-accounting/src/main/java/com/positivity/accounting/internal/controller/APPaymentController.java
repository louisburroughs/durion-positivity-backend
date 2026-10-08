package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.APPaymentGLPostingRetryRequest;
import com.positivity.accounting.internal.dto.APPaymentResponse;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.dto.VendorBillSummaryResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.APPaymentService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller for AP (Accounts Payable) payment operations.
 *
 * <p>
 * Endpoints:
 * <ul>
 * <li>POST /v1/accounting/ap/payments - Execute vendor payment</li>
 * <li>POST /v1/accounting/ap/payments/{paymentId}/gl-posting-retry - Post a refused payment again (CAP:550 S42)</li>
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
                Executes an AP vendor payment through the payment gateway from a functional-currency BANK_CASH \
                account, optionally allocating it across approved vendor bills; the outbox then posts Dr 2000 the \
                gross, Dr 6030 the fee and Cr the bank account on the payment's business date (AP_PAYMENT category).
                Use this tool to pay a vendor; do not use applyPayment, which is the AR-side application of customer \
                payments to invoices, and use listApBills first to find APPROVED bills to allocate against.
                Preconditions: checked in this order before the gateway is called, charging nothing, the method \
                is ACH, CHECK or WIRE, the currency is the functional currency, the bank account is eligible (active \
                from the start of the business date, not deactivated before the payment, not in a foreign currency), the \
                vendor is an ACTIVE pos-supplier vendor in accounting's copy, every allocated bill exists, is \
                APPROVED, belongs to the vendor and fits the gross amount, the payer approved none of the bills paid \
                (unless the AP approval policy allows it), every bill was approved at the vendor's current remit-to \
                version or someone other than the payer confirmed it, the business date is not hard-locked, its period is open or overridden, \
                and the AP_PAYMENT mappings ACCOUNTS_PAYABLE (and PAYMENT_FEES when a fee is charged) are set up.
                Required inputs: vendorId (UUID), grossAmount (min 0.01), currency (ISO 4217), paymentRef (max 100 \
                chars, the idempotency key) and paymentMethod; bankAccountId may be omitted only when exactly one \
                eligible account exists, and feeAmount, overrideJustification (10-1000 chars, honoured with \
                accounting:period:override), paymentSource, memo and explicit allocations are optional.
                Emits an AP_PAYMENT_EXECUTE event; the call is idempotent on paymentRef, replaying the same ref with \
                the same payload (the bank account compared as resolved) as a 200 instead of paying twice, and a \
                gateway failure or timeout leaves no payment behind, so the same paymentRef is simply sent again.
                Returns 400 VALIDATION_ERROR for a malformed body, an unknown currency code, a refused allocation \
                or fieldErrors[bankAccountId], 403 AP_PAYMENT_SELF_APPROVED_BILL, 409 IDEMPOTENCY_CONFLICT, \
                LOCK_TIMEOUT or VENDOR_PAYMENT_DETAILS_CHANGED (naming each bill and the vendor number), 422 \
                AP_PAYMENT_METHOD_NOT_SUPPORTED, CURRENCY_NOT_SUPPORTED, VENDOR_INACTIVE, \
                ACCOUNTING_TIME_ZONE_UNSET, PERIOD_HARD_LOCKED, PERIOD_CLOSED or GL_MAPPING_NOT_CONFIGURED, and 500 \
                PAYMENT_GATEWAY_FAILURE when the gateway fails or times out, and 503 VENDOR_REPLICATION_PENDING \
                (Retry-After) when the vendor is not in the copy yet.
                """,
            tags = {"AP Payments"})
    @ApiResponse(responseCode = "200", description = "Idempotent replay: existing payment returned")
    @ApiResponse(responseCode = "201", description = "Payment executed successfully (new payment created)")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: a malformed body, a currency that is not ISO 4217, a bill missing,"
                    + " unapproved, another vendor's or over-allocated, or fieldErrors[bankAccountId] when the bank"
                    + " account is missing and not exactly one is eligible, or the one supplied is not eligible;"
                    + " nothing is charged",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN without accounting:ap:pay, or AP_PAYMENT_SELF_APPROVED_BILL: the payer approved a"
                    + " bill the payment would pay (fieldErrors[selfApprovedBillNumbers] name them); nothing is paid",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "IDEMPOTENCY_CONFLICT: paymentRef exists with a different payload; LOCK_TIMEOUT: another"
                    + " request held these bills beyond accounting.ap.lock-timeout, the payment was not saved, retry;"
                    + " VENDOR_PAYMENT_DETAILS_CHANGED: a bill was approved at another remit-to version and no one but"
                    + " the payer confirmed the current one (fieldErrors name the bills); nothing is paid",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Refused before the gateway, in this order: AP_PAYMENT_METHOD_NOT_SUPPORTED (CREDIT_CARD,"
                    + " OTHER), CURRENCY_NOT_SUPPORTED (not the functional currency), VENDOR_INACTIVE,"
                    + " ACCOUNTING_TIME_ZONE_UNSET,"
                    + " PERIOD_HARD_LOCKED, PERIOD_CLOSED (no overrideJustification with accounting:period:override),"
                    + " GL_MAPPING_NOT_CONFIGURED (AP_PAYMENT/ACCOUNTS_PAYABLE, or PAYMENT_FEES with a fee); nothing"
                    + " is charged or persisted",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description =
                    "VENDOR_REPLICATION_PENDING: the vendor is not in accounting's copy of the pos-supplier vendor"
                            + " master yet. Not-yet, not no: retry after the Retry-After interval.",
            headers =
                    @Header(
                            name = "Retry-After",
                            description = "Seconds to wait before retrying",
                            schema = @Schema(type = "integer")),
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "500",
            description = "PAYMENT_GATEWAY_FAILURE: the gateway failed or timed out; the outcome is unknown, nothing"
                    + " was persisted, and the same paymentRef is sent again (its idempotency key replays the outcome)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
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
                                                                 "feeAmount":1.50,
                                                                 "currency":"USD",
                                                                 "bankAccountId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60",
                                                                 "paymentRef":"ap-pay-2026-08-13-007",
                                                                 "paymentMethod":"ACH",
                                                                 "allocations":[
                                                                   {"vendorBillId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                    "appliedAmount":250.00}]}
                                                                """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    ExecuteAPPaymentRequest request) {

        // The payer in the form every vendor-bill decision records its actor (ADR-0018), so the pay guard compares
        // payer and approver alike (CAP:550 S13, #2510).
        String currentUser = SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault("system")
                : "system";
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

    @PostMapping("/payments/{paymentId}/gl-posting-retry")
    @EmitEvent(id = "ACCOUNTING_AP_PAYMENT_GL_POSTING_RETRY", apiVersion = "1")
    @Operation(
            operationId = "retryApPaymentGlPosting",
            summary = "Retry AP Payment GL Posting",
            description = """
                Posts again the ledger entry of an executed AP payment whose posting was refused, on the payment's \
                own stored date: Dr 2000 the gross, Dr 6030 the fee, Cr the bank account it was paid from.
                Use this tool once the reason in glPostError is fixed (a GL mapping set up, a period reopened, or \
                with an override); do not use executeApPayment again, which would pay the vendor twice, and do not \
                use the journal-entry endpoints instead, which would post the payment outside its own record.
                Preconditions: the payment exists and is GL_POST_FAILED; the payment row is locked for the retry, \
                and the entry is never re-dated, so a payment whose date is now hard-locked stays GL_POST_FAILED.
                Required inputs: paymentId (UUID) as a path parameter and an optional body with \
                overrideJustification (10-1000 chars, honoured with the caller's accounting:period:override and \
                audited under the caller); a retry never reuses the override the payer gave on the pay command.
                Emits ACCOUNTING_AP_PAYMENT_GL_POSTING_RETRY; on success the payment is GL_POSTED with its journal \
                entry id, and a refused retry leaves it GL_POST_FAILED with the new reason in glPostError.
                Returns 404 NOT_FOUND when no such payment exists, 409 AP_PAYMENT_NOT_RETRYABLE when it is not \
                GL_POST_FAILED (already posted, pending, or a gateway state), 409 LOCK_TIMEOUT when another request \
                holds it, and 422 GL_MAPPING_NOT_CONFIGURED, GL_ACCOUNT_NOT_ACTIVE, PERIOD_CLOSED, PERIOD_HARD_LOCKED \
                or ACCOUNTING_TIME_ZONE_UNSET when the posting is still refused.
                """,
            tags = {"AP Payments"})
    @ApiResponse(responseCode = "200", description = "Posted: the payment is GL_POSTED with its journal entry id")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: overrideJustification under 10 or over 1000 characters",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN without accounting:je:post",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "NOT_FOUND: no such AP payment",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "AP_PAYMENT_NOT_RETRYABLE: the payment is not GL_POST_FAILED; LOCK_TIMEOUT: another request"
                    + " held the payment or the period row (posting's period gate) beyond accounting.ap.lock-timeout",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description =
                    "The posting is still refused: GL_MAPPING_NOT_CONFIGURED, GL_ACCOUNT_NOT_ACTIVE, PERIOD_CLOSED (no"
                            + " overrideJustification of the caller's own), PERIOD_HARD_LOCKED or"
                            + " ACCOUNTING_TIME_ZONE_UNSET; the payment stays GL_POST_FAILED with this code in glPostError",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:je:post"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.JE_POST + "')")
    public @NonNull ResponseEntity<APPaymentResponse> retryGlPosting(
            @PathVariable
                    @Parameter(description = "Payment UUID", example = "01936e5c-7890-7a3d-8b6e-2b3456789012")
                    @NonNull
                    UUID paymentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional closed-period override of the caller; may be omitted.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Retry with an override", value = """
                                                                {"overrideJustification":"June reopened for audit; posting the vendor payment"}
                                                                """)))
                    @Valid
                    @RequestBody(required = false)
                    @Nullable
                    APPaymentGLPostingRetryRequest request) {
        String override = request == null ? null : request.getOverrideJustification();
        return ResponseEntity.ok(apPaymentService.retryGLPosting(paymentId, override));
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
    @ApiResponse(
            responseCode = "404",
            description = "Payment not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AP payment not found"));
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
    @ApiResponse(
            responseCode = "404",
            description = "Payment not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AP payment not found"));
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
    @ApiResponse(
            responseCode = "400",
            description = "Invalid vendor ID",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
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
