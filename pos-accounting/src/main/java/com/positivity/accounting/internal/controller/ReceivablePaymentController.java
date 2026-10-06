package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.UnappliedPaymentsPage;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.ReceivablesWorklistService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The unapplied-payments list (#2502; spec §7.1 "Unapplied payments", G3): every customer payment
 * still waiting to be matched, with the invoices it most likely pays and why.
 */
@Slf4j
@RestController
@RequestMapping("/v1/accounting")
@RequiredArgsConstructor
@Tag(name = "Payment Applications", description = "Manage payment applications to invoices (AR)")
public class ReceivablePaymentController {

    static final String AVAILABLE = "AVAILABLE";
    static final int MAX_PAGE_SIZE = 100;

    private final ReceivablesWorklistService receivablesWorklistService;

    @GetMapping("/receivable-payments")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:payment:apply"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.PAYMENT_APPLY + "')")
    @Operation(
            operationId = "listUnappliedPayments",
            summary = "List Unapplied Customer Payments",
            description = """
                    Lists the customer payments still waiting to be matched, oldest cleared first, each with \
                    the open invoices it most likely pays, the reasons, and what would be left over as credit.
                    Use this tool to choose a payment to match; then apply it with applyPaymentToInvoices, \
                    and do not treat a suggestion as applied, because the list is advisory and the apply \
                    command validates again.
                    Preconditions: the caller needs accounting:payment:apply authority; only payments with an \
                    unapplied amount (status AVAILABLE) are listed.
                    Required inputs: none; status (only AVAILABLE), customerId (UUID), page (0 or more) and \
                    size (1 to 100, default 25) are optional query parameters.
                    Emits an ACCOUNTING_RECEIVABLE_PAYMENT_LIST_VIEW event and changes no state; the summary \
                    totals cover every payment matching the filter, not only the page.
                    Returns 400 VALIDATION_ERROR when status is not AVAILABLE, customerId is malformed or size \
                    is outside 1 to 100, and 403 when the caller lacks the authority.
                    """,
            tags = {"Payment Applications"})
    @ApiResponse(responseCode = "200", description = "A page of unapplied payments with suggestions and a summary")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: status other than AVAILABLE, malformed customerId, or size outside 1-100",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:payment:apply",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_RECEIVABLE_PAYMENT_LIST_VIEW", apiVersion = "1")
    public ResponseEntity<UnappliedPaymentsPage> listUnappliedPayments(
            @Parameter(description = "Payment status; only AVAILABLE is supported", example = "AVAILABLE")
                    @RequestParam(defaultValue = AVAILABLE)
                    String status,
            @Parameter(description = "Only this customer's payments", example = "0198a000-0000-7000-8000-000000000412")
                    @RequestParam(required = false)
                    @Nullable
                    UUID customerId,
            @Parameter(description = "Page index (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, 1 to 100", example = "25") @RequestParam(defaultValue = "25")
                    int size) {
        if (!AVAILABLE.equals(status)) {
            throw new InvalidRequestParameterException("status must be AVAILABLE");
        }
        PageParameters.check(page, size, MAX_PAGE_SIZE);
        log.debug("Listing unapplied payments: customerFilter={}, page={}, size={}", customerId != null, page, size);
        return ResponseEntity.ok(receivablesWorklistService.listUnappliedPayments(customerId, page, size));
    }
}
