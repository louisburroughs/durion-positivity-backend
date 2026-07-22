package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.CreateCreditMemoRequest;
import com.positivity.accounting.internal.dto.CreditMemoResponse;
import com.positivity.accounting.internal.dto.VoidCreditMemoRequest;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.service.CreditMemoService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    @PreAuthorize("hasAuthority('accounting:credit-memo:create')")
    @Operation(
            summary = "Create credit memo",
            description = "Create a new Credit Memo to reverse invoice charges. "
                    + "Reduces Accounts Receivable by posting offsetting GL entries. "
                    + "Requires finalized invoice and credit amount within outstanding balance.",
            tags = {"Credit Memos"})
    @ApiResponse(responseCode = "201", description = "Credit memo created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request - validation errors")
    @ApiResponse(responseCode = "404", description = "Invoice not found")
    @ApiResponse(
            responseCode = "409",
            description = "Business rule violation - amount exceeds balance or invoice not finalized")
    @EmitEvent(id = "ACCOUNTING_CREDIT_MEMO_CREATE", apiVersion = "1")
    public ResponseEntity<CreditMemoResponse> createCreditMemo(@Valid @RequestBody CreateCreditMemoRequest request) {

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
    @PreAuthorize("hasAuthority('accounting:credit-memo:void')")
    @Operation(
            summary = "Void a posted credit memo",
            description = "Void a POSTED Credit Memo: posts the mirror GL entry (Dr AR / Cr Revenue + Cr Sales-Tax"
                    + " Payable) dated at void time and restores the invoice's outstanding balance. The memo's"
                    + " original posting-period figures are never restated; the tax-liability report restores the"
                    + " reversed tax in the void's period. Only POSTED memos are voidable — APPLIED memos have been"
                    + " consumed and VOIDED is terminal.",
            tags = {"Credit Memos"})
    @ApiResponse(responseCode = "200", description = "Credit memo voided")
    @ApiResponse(responseCode = "400", description = "Missing or invalid void reason")
    @ApiResponse(responseCode = "404", description = "Credit memo not found")
    @ApiResponse(responseCode = "409", description = "Credit memo is not in POSTED status")
    @EmitEvent(id = "ACCOUNTING_CREDIT_MEMO_VOID", apiVersion = "1")
    public ResponseEntity<CreditMemoResponse> voidCreditMemo(
            @Parameter(description = "Credit Memo id", required = true) @PathVariable UUID creditMemoId,
            @Valid @RequestBody VoidCreditMemoRequest request) {

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
    @PreAuthorize("hasAuthority('accounting:credit-memo:read')")
    @Operation(
            summary = "List credit memos",
            description = "Retrieve paginated credit memos with optional filters. "
                    + "Filter by customer, invoice, or status. "
                    + "Supports standard pagination parameters (page, size, sort).",
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
            Pageable pageable) {

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
    @PreAuthorize("hasAuthority('accounting:credit-memo:read')")
    @Operation(
            summary = "Get credit memo",
            description = "Retrieve details for a specific Credit Memo by ID. "
                    + "Includes full audit trail and current invoice balance.",
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
