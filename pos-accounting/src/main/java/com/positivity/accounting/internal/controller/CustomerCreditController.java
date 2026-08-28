package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.CustomerCreditApplicationRequest;
import com.positivity.accounting.internal.dto.CustomerCreditRefundRequest;
import com.positivity.accounting.internal.dto.CustomerCreditResponse;
import com.positivity.accounting.internal.dto.CustomerCreditTransactionResponse;
import com.positivity.accounting.internal.enums.CustomerCreditStatus;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.CustomerCreditService;
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

/**
 * REST endpoints for AR customer credits and their draw-down (issue #992).
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code GET  /v1/accounting/customer-credits} — list credits</li>
 *   <li>{@code GET  /v1/accounting/customer-credits/{creditId}} — one credit</li>
 *   <li>{@code POST /v1/accounting/customer-credits/{creditId}/applications} — apply to an invoice</li>
 *   <li>{@code POST /v1/accounting/customer-credits/{creditId}/refunds} — refund to the customer</li>
 * </ul>
 *
 * <p>Both draw-down endpoints relieve the Customer Credit Liability (2300) recognized at
 * issuance (#975), which is what makes the subledger↔GL invariant hold across the full credit
 * lifecycle.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/992">Issue #992</a>
 */
@Slf4j
@RestController
@RequestMapping("/v1/accounting/customer-credits")
@RequiredArgsConstructor
@Tag(name = "Customer Credits", description = "AR customer credits: balances, application to invoices, and refunds")
@Validated
public class CustomerCreditController {

    private final CustomerCreditService customerCreditService;

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:customer-credit:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.CUSTOMER_CREDIT_VIEW + "')")
    @Operation(
            operationId = "listCustomerCredits",
            summary = "List Customer Credits",
            description = """
                    Lists AR customer credits with their remaining open amounts as a paginated projection, \
                    optionally filtered by customer and consumption state.
                    Use this tool to find open credit before applying or refunding; do not use \
                    getCustomerCredit, which fetches one credit by its known id.
                    Preconditions: none beyond the caller holding accounting:customer-credit:view.
                    Required inputs: none; customerId and status are optional filters, with standard page, \
                    size and sort parameters.
                    Emits an ACCOUNTING_CUSTOMER_CREDIT_LIST audit event; no state changes.
                    Returns 200 with an empty page when nothing matches the filters.
                    """,
            tags = {"Customer Credits"})
    @ApiResponse(responseCode = "200", description = "Credits returned")
    @EmitEvent(id = "ACCOUNTING_CUSTOMER_CREDIT_LIST", apiVersion = "1")
    public ResponseEntity<Page<CustomerCreditResponse>> listCustomerCredits(
            @Parameter(description = "Filter by customer") @RequestParam(required = false) UUID customerId,
            @Parameter(description = "Filter by consumption state") @RequestParam(required = false)
                    CustomerCreditStatus status,
            @ParameterObject Pageable pageable) {
        return ResponseEntity.ok(customerCreditService.listCredits(customerId, status, pageable));
    }

    @GetMapping("/{creditId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:customer-credit:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.CUSTOMER_CREDIT_VIEW + "')")
    @Operation(
            operationId = "getCustomerCredit",
            summary = "Get Customer Credit",
            description = """
                    Returns one AR customer credit with its applied and refunded totals and the remaining \
                    open amount.
                    Use this tool when the credit id is already known; use listCustomerCredits instead when \
                    searching by customer or consumption state.
                    Preconditions: the customer credit must exist.
                    Required inputs: creditId (UUID) as a path parameter; there is no request body.
                    Emits an ACCOUNTING_CUSTOMER_CREDIT_GET audit event; no state changes.
                    Returns 404 when no customer credit exists for the supplied id.
                    """,
            tags = {"Customer Credits"})
    @ApiResponse(responseCode = "200", description = "Credit returned")
    @ApiResponse(
            responseCode = "404",
            description = "Credit not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_CUSTOMER_CREDIT_GET", apiVersion = "1")
    public ResponseEntity<CustomerCreditResponse> getCustomerCredit(
            @Parameter(description = "Credit identifier") @PathVariable UUID creditId) {
        return ResponseEntity.ok(customerCreditService.getCredit(creditId));
    }

    @PostMapping("/{creditId}/applications")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:customer-credit:apply"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.CUSTOMER_CREDIT_APPLY + "')")
    @Operation(
            operationId = "applyCustomerCredit",
            summary = "Apply Customer Credit To Invoice",
            description = """
                    Draws an open customer credit down against an outstanding invoice, posting debit Customer \
                    Credit Liability, credit Accounts Receivable for the applied amount.
                    Use this tool to consume standing credit against an invoice; do not use \
                    refundCustomerCredit, which pays the credit back as cash, and do not use \
                    createCreditMemo, which issues new credit against an invoice.
                    Preconditions: the credit must have sufficient open amount, the invoice must be \
                    AR-eligible and belong to the same customer, the amount must not exceed the invoice \
                    balance, and the accounting period must be open.
                    Required inputs: creditId (UUID) as a path parameter, requestId (max 100 chars, the \
                    idempotency key), invoiceId (UUID) and amount (min 0.01).
                    Emits an ACCOUNTING_CUSTOMER_CREDIT_APPLY event; replaying the same requestId returns \
                    the original application instead of double-applying.
                    Returns 404 when the credit or invoice is not found, 409 for insufficient open credit, \
                    an ineligible or foreign invoice, an amount over the invoice balance, a closed period, or \
                    a requestId reused for a different operation, and 400 when the amount is missing or \
                    non-positive.
                    """,
            tags = {"Customer Credits"})
    @ApiResponse(responseCode = "200", description = "Credit applied (or the original application replayed)")
    @ApiResponse(
            responseCode = "400",
            description = "Amount missing or non-positive",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Credit or invoice not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Insufficient open credit, invoice not AR-eligible, invoice belongs to another customer, "
                    + "amount exceeds the invoice balance, accounting period not open, or the request id was already "
                    + "used for a different operation",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_CUSTOMER_CREDIT_APPLY", apiVersion = "1")
    public ResponseEntity<CustomerCreditTransactionResponse> applyCustomerCredit(
            @Parameter(description = "Credit identifier") @PathVariable UUID creditId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Idempotent draw-down of the credit against one outstanding invoice.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Apply to invoice", value = """
                                                                    {"requestId":"apply-2026-08-13-001",
                                                                     "invoiceId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "amount":25.00}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CustomerCreditApplicationRequest request) {
        log.info("Applying customer credit {} to invoice {}", creditId, request.getInvoiceId());
        return ResponseEntity.ok(customerCreditService.applyCreditToInvoice(creditId, request, currentUser()));
    }

    @PostMapping("/{creditId}/refunds")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:customer-credit:refund"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.CUSTOMER_CREDIT_REFUND + "')")
    @Operation(
            operationId = "refundCustomerCredit",
            summary = "Refund Customer Credit",
            description = """
                    Refunds an open customer credit as cash back to the customer, posting debit Customer \
                    Credit Liability, credit Undeposited Funds for the refunded amount.
                    Use this tool to pay standing credit out; do not use applyCustomerCredit, which consumes \
                    the credit against an invoice instead.
                    Preconditions: the credit must have sufficient open amount and the accounting period must \
                    be open.
                    Required inputs: creditId (UUID) as a path parameter, requestId (max 100 chars, the \
                    idempotency key) and amount (min 0.01); note (max 500 chars) is optional.
                    Emits an ACCOUNTING_CUSTOMER_CREDIT_REFUND event; replaying the same requestId returns \
                    the original refund instead of double-paying.
                    Returns 404 when the credit is not found, 409 for insufficient open credit, a closed \
                    period, or a requestId reused for a different operation, and 400 when the amount is \
                    missing or non-positive.
                    """,
            tags = {"Customer Credits"})
    @ApiResponse(responseCode = "200", description = "Credit refunded (or the original refund replayed)")
    @ApiResponse(
            responseCode = "400",
            description = "Amount missing or non-positive",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Credit not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Insufficient open credit, accounting period not open, or the request id was already used "
                    + "for a different operation",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_CUSTOMER_CREDIT_REFUND", apiVersion = "1")
    public ResponseEntity<CustomerCreditTransactionResponse> refundCustomerCredit(
            @Parameter(description = "Credit identifier") @PathVariable UUID creditId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Idempotent cash refund of part or all of the open credit.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Refund remainder", value = """
                                                                    {"requestId":"refund-2026-08-13-001",
                                                                     "amount":15.00,
                                                                     "note":"Customer requested cash back"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CustomerCreditRefundRequest request) {
        log.info("Refunding customer credit {}", creditId);
        return ResponseEntity.ok(customerCreditService.refundCredit(creditId, request, currentUser()));
    }

    private String currentUser() {
        return SecurityContextHelper.getCurrentUsernameOrDefault("SYSTEM");
    }
}
