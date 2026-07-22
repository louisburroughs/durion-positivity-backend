package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.CustomerCreditApplicationRequest;
import com.positivity.accounting.internal.dto.CustomerCreditRefundRequest;
import com.positivity.accounting.internal.dto.CustomerCreditResponse;
import com.positivity.accounting.internal.dto.CustomerCreditTransactionResponse;
import com.positivity.accounting.internal.enums.CustomerCreditStatus;
import com.positivity.accounting.service.CustomerCreditService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    @PreAuthorize("hasAuthority('accounting:customer-credit:view')")
    @Operation(
            summary = "List customer credits",
            description = "List AR customer credits with their remaining open amounts, optionally filtered by "
                    + "customer and consumption state.",
            tags = {"Customer Credits"})
    @ApiResponse(responseCode = "200", description = "Credits returned")
    @EmitEvent(id = "ACCOUNTING_CUSTOMER_CREDIT_LIST", apiVersion = "1")
    public ResponseEntity<Page<CustomerCreditResponse>> listCustomerCredits(
            @Parameter(description = "Filter by customer") @RequestParam(required = false) UUID customerId,
            @Parameter(description = "Filter by consumption state") @RequestParam(required = false)
                    CustomerCreditStatus status,
            Pageable pageable) {
        return ResponseEntity.ok(customerCreditService.listCredits(customerId, status, pageable));
    }

    @GetMapping("/{creditId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:customer-credit:view"})
    @PreAuthorize("hasAuthority('accounting:customer-credit:view')")
    @Operation(
            summary = "Get customer credit",
            description = "Fetch one AR customer credit with its applied/refunded totals and remaining open amount.",
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
    @PreAuthorize("hasAuthority('accounting:customer-credit:apply')")
    @Operation(
            summary = "Apply customer credit to an invoice",
            description = "Draw an open customer credit down against an outstanding invoice. Posts "
                    + "Dr Customer Credit Liability / Cr Accounts Receivable for the applied amount. Idempotent "
                    + "on requestId.",
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
            @Valid @RequestBody CustomerCreditApplicationRequest request) {
        log.info("Applying customer credit {} to invoice {}", creditId, request.getInvoiceId());
        return ResponseEntity.ok(customerCreditService.applyCreditToInvoice(creditId, request, currentUser()));
    }

    @PostMapping("/{creditId}/refunds")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:customer-credit:refund"})
    @PreAuthorize("hasAuthority('accounting:customer-credit:refund')")
    @Operation(
            summary = "Refund customer credit",
            description = "Refund an open customer credit as cash back to the customer. Posts "
                    + "Dr Customer Credit Liability / Cr Undeposited Funds for the refunded amount. Idempotent "
                    + "on requestId.",
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
            @Valid @RequestBody CustomerCreditRefundRequest request) {
        log.info("Refunding customer credit {}", creditId);
        return ResponseEntity.ok(customerCreditService.refundCredit(creditId, request, currentUser()));
    }

    private String currentUser() {
        return SecurityContextHelper.getCurrentUsernameOrDefault("SYSTEM");
    }
}
