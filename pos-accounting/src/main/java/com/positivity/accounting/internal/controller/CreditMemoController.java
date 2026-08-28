package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.CreateCreditMemoRequest;
import com.positivity.accounting.internal.dto.CreditMemoResponse;
import com.positivity.accounting.internal.dto.VoidCreditMemoRequest;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.CreditMemoService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
 * REST Controller for Credit Memo operations (AR corrections).
 *
 * Endpoints:
 * - POST /v1/accounting/credit-memos - Create Credit Memo
 * - GET /v1/accounting/credit-memos - List Credit Memos (paginated, filterable)
 * - GET /v1/accounting/credit-memos/{creditMemoId} - Get Credit Memo details
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/131">Issue
 *      #131</a>
 */
@Slf4j
@RestController
@RequestMapping("/v1/accounting/credit-memos")
@RequiredArgsConstructor
@Tag(name = "Credit Memos", description = "Manage credit memos for AR corrections")
@Validated
public class CreditMemoController {

    private final CreditMemoService creditMemoService;

    /**
     * Create a Credit Memo to reverse invoice charges.
     *
     * Business Rules:
     * - Credit Memo must reference a finalized invoice
     * - Credit amount cannot exceed invoice outstanding balance
     * - Reason code is mandatory for audit trail
     * - Posts reversing GL entries (debit revenue + tax, credit AR)
     * - Updates invoice outstanding balance
     *
     * @param request Credit Memo creation request
     * @return Created Credit Memo details
     */
    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:credit-memo:create"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.CREDIT_MEMO_CREATE + "')")
    @Operation(
            operationId = "createCreditMemo",
            summary = "Create Credit Memo",
            description = """
                    Creates and posts a credit memo against a finalized invoice, reversing invoice charges by \
                    posting debit revenue and tax, credit Accounts Receivable, and reducing the invoice's \
                    outstanding balance.
                    Use this tool for AR corrections against a specific invoice; do not use \
                    applyCustomerCredit, which draws down a standing customer credit, and do not use \
                    voidCreditMemo, which backs out a memo already posted.
                    Preconditions: the invoice must exist and be finalized, and creditAmount must not exceed \
                    the invoice's outstanding balance.
                    Required inputs: originalInvoiceId (UUID), creditAmount (min 0.01) and reasonCode (max 50 \
                    chars, kept for the audit trail); justificationNote (max 1000 chars) is optional.
                    Emits an ACCOUNTING_CREDIT_MEMO_CREATE event and posts the reversing GL entries.
                    Returns 404 when the invoice is not found, 409 when the amount exceeds the outstanding \
                    balance or the invoice is not finalized, and 401 when no authenticated user is present.
                    """,
            tags = {"Credit Memos"})
    @ApiResponse(responseCode = "201", description = "Credit memo created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request - validation errors")
    @ApiResponse(responseCode = "404", description = "Invoice not found")
    @ApiResponse(
            responseCode = "409",
            description = "Business rule violation - amount exceeds balance or invoice not finalized")
    @EmitEvent(id = "ACCOUNTING_CREDIT_MEMO_CREATE", apiVersion = "1")
    public ResponseEntity<CreditMemoResponse> createCreditMemo(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Credit to issue against a finalized invoice, with the audit reason code.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Partial credit", value = """
                                                                    {"originalInvoiceId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "creditAmount":45.00,
                                                                     "reasonCode":"BILLING_ERROR",
                                                                     "justificationNote":"Labor line billed twice"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateCreditMemoRequest request) {

        String currentUser = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated"));

        log.info(
                "Creating Credit Memo for invoice {} requested by user {}",
                request.getOriginalInvoiceId(),
                currentUser);

        CreditMemoResponse response = creditMemoService.createCreditMemo(request, currentUser);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Void a POSTED Credit Memo (issue #997 symmetry).
     *
     * The memo keeps its posting-period contribution to the tax-liability report (no retroactive
     * restatement of closed/frozen periods); the void posts the mirror journal entry
     * (Dr AR / Cr Revenue + Cr Sales-Tax Payable) dated now, and the report restores the reversed
     * tax in the void's period — GL drift stays zero on both sides of the transition. The
     * invoice's outstanding balance is restored automatically.
     *
     * @param creditMemoId Credit Memo to void
     * @param request      Void request (mandatory reason)
     * @return Voided Credit Memo details
     */
    @PostMapping("/{creditMemoId}/void")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:credit-memo:void"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.CREDIT_MEMO_VOID + "')")
    @Operation(
            operationId = "voidCreditMemo",
            summary = "Void Credit Memo",
            description = """
                    Voids a POSTED credit memo by posting the mirror GL entry (debit AR, credit Revenue and \
                    Sales-Tax Payable) dated at void time, restoring the invoice's outstanding balance.
                    Use this tool to back out a memo issued in error; do not use createCreditMemo, which \
                    issues new credit, and note that APPLIED memos have been consumed and cannot be voided.
                    Preconditions: the credit memo must exist and be in POSTED status; VOIDED is terminal.
                    Required inputs: creditMemoId (UUID) as a path parameter and a non-blank voidReason (max \
                    1000 chars).
                    Emits an ACCOUNTING_CREDIT_MEMO_VOID event; the memo's original posting-period figures \
                    are never restated, and the tax-liability report restores the reversed tax in the void's \
                    period so GL drift stays zero.
                    Returns 404 when the memo is not found, 409 when it is not POSTED, and 400 when the void \
                    reason is missing or blank.
                    """,
            tags = {"Credit Memos"})
    @ApiResponse(
            responseCode = "200",
            description = "Credit memo voided",
            content = @Content(schema = @Schema(implementation = CreditMemoResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Missing or invalid void reason",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Credit memo not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Credit memo is not in POSTED status",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_CREDIT_MEMO_VOID", apiVersion = "1")
    public ResponseEntity<CreditMemoResponse> voidCreditMemo(
            @Parameter(description = "Credit Memo id", required = true) @PathVariable UUID creditMemoId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Mandatory audit reason for voiding the posted memo.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Void issued in error",
                                                            value =
                                                                    "{\"voidReason\":\"Memo issued against wrong invoice\"}")))
                    @Valid
                    @RequestBody
                    VoidCreditMemoRequest request) {

        String currentUser = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated"));

        log.info("Voiding Credit Memo {} requested by user {}", creditMemoId, currentUser);

        return ResponseEntity.ok(creditMemoService.voidCreditMemo(creditMemoId, request.getVoidReason(), currentUser));
    }

    /**
     * List Credit Memos with optional filters.
     *
     * Supports pagination and filtering by:
     * - customerId - Show credit memos for a specific customer
     * - originalInvoiceId - Show credit memos for a specific invoice
     * - status - Filter by Credit Memo status (DRAFT, POSTED, APPLIED, VOIDED)
     *
     * @param customerId        Optional customer filter
     * @param originalInvoiceId Optional invoice filter
     * @param status            Optional status filter
     * @param pageable          Pagination parameters
     * @return Paginated Credit Memo list
     */
    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:credit-memo:read"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.CREDIT_MEMO_READ + "')")
    @Operation(
            operationId = "listCreditMemos",
            summary = "List Credit Memos",
            description = """
                    Lists credit memos as a paginated projection, optionally filtered by customer, original \
                    invoice or lifecycle status.
                    Use this tool when browsing or reconciling memos; do not use getCreditMemo, which fetches \
                    one memo by its known id.
                    Preconditions: none beyond the caller holding accounting:credit-memo:read.
                    Required inputs: none; customerId, originalInvoiceId and status (DRAFT, POSTED, APPLIED, \
                    VOIDED) are optional filters, with standard page, size and sort parameters.
                    Emits an ACCOUNTING_CREDIT_MEMO_LIST audit event; no state changes.
                    Returns 400 when pagination or filter parameters are invalid.
                    """,
            tags = {"Credit Memos"})
    @ApiResponse(responseCode = "200", description = "Credit memos retrieved successfully")
    @ApiResponse(responseCode = "400", description = "Invalid pagination or filter parameters")
    @EmitEvent(id = "ACCOUNTING_CREDIT_MEMO_LIST", apiVersion = "1")
    public ResponseEntity<Page<CreditMemoResponse>> listCreditMemos(
            @Parameter(description = "Filter by customer ID") @RequestParam(required = false) UUID customerId,
            @Parameter(description = "Filter by original invoice ID") @RequestParam(required = false)
                    UUID originalInvoiceId,
            @Parameter(description = "Filter by status (DRAFT, POSTED, APPLIED, VOIDED)")
                    @RequestParam(required = false)
                    CreditMemoStatus status,
            @ParameterObject Pageable pageable) {

        log.debug(
                "Listing Credit Memos: customerId={}, invoiceId={}, status={}, page={}",
                customerId,
                originalInvoiceId,
                status,
                pageable);

        Page<CreditMemoResponse> results =
                creditMemoService.listCreditMemos(customerId, originalInvoiceId, status, pageable);

        return ResponseEntity.ok(results);
    }

    /**
     * Get a Credit Memo by ID.
     *
     * @param creditMemoId Credit Memo identifier
     * @return Credit Memo details
     */
    @GetMapping("/{creditMemoId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:credit-memo:read"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.CREDIT_MEMO_READ + "')")
    @Operation(
            operationId = "getCreditMemo",
            summary = "Get Credit Memo",
            description = """
                    Returns one credit memo with its amounts, status, audit trail and the invoice's current \
                    balance.
                    Use this tool when the memo id is already known; use listCreditMemos instead when \
                    searching by customer, invoice or status.
                    Preconditions: the credit memo must exist.
                    Required inputs: creditMemoId (UUID) as a path parameter; there is no request body.
                    Emits an ACCOUNTING_CREDIT_MEMO_GET audit event; no state changes.
                    Returns 404 when no credit memo exists for the supplied id.
                    """,
            tags = {"Credit Memos"})
    @ApiResponse(responseCode = "200", description = "Credit memo retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Credit memo not found")
    @EmitEvent(id = "ACCOUNTING_CREDIT_MEMO_GET", apiVersion = "1")
    public ResponseEntity<CreditMemoResponse> getCreditMemo(
            @Parameter(description = "Credit Memo ID", required = true) @PathVariable UUID creditMemoId) {

        log.debug("Fetching Credit Memo {}", creditMemoId);

        CreditMemoResponse response = creditMemoService.getCreditMemo(creditMemoId);

        return ResponseEntity.ok(response);
    }
}
