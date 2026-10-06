package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.CustomerOpenInvoicesPage;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A customer's open invoices with what is still owed (#2502; spec §7.1 "Eligible invoices", G4;
 * AGENT_GUIDE E2, E3): the invoices a payment of that customer can be applied to.
 */
@Slf4j
@RestController
@RequestMapping("/v1/accounting")
@RequiredArgsConstructor
@Tag(name = "Payment Applications", description = "Manage payment applications to invoices (AR)")
public class CustomerReceivablesController {

    static final int MAX_PAGE_SIZE = 200;

    private final ReceivablesWorklistService receivablesWorklistService;

    @GetMapping("/customers/{customerId}/open-invoices")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:payment:apply"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.PAYMENT_APPLY + "')")
    @Operation(
            operationId = "listCustomerOpenInvoices",
            summary = "List a Customer's Open Invoices",
            description = """
                    Lists a customer's open invoices oldest first, each with the balance still due after \
                    payment applications, customer credits, posted credit memos and deposits, and whether it \
                    is overdue.
                    Use this tool to choose the invoices a payment of this customer pays; do not use \
                    pos-invoice search for this, because it does not net credits, credit memos or deposits.
                    Preconditions: the caller needs accounting:payment:apply authority; an invoice is open \
                    when it is FINALIZED or POSTED and its balance due is above zero.
                    Required inputs: customerId (UUID) as a path parameter; page (0 or more) and size (1 to \
                    200, default 100) are optional query parameters.
                    Emits an ACCOUNTING_CUSTOMER_OPEN_INVOICES_VIEW event and changes no state; a customer \
                    with nothing open, or unknown to accounting, gets 200 with no rows.
                    Returns 400 VALIDATION_ERROR when customerId is malformed or size is outside 1 to 200, \
                    and 403 when the caller lacks the authority.
                    """,
            tags = {"Payment Applications"})
    @ApiResponse(responseCode = "200", description = "A page of the customer's open invoices with a summary")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: malformed customerId, or size outside 1-200",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:payment:apply",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_CUSTOMER_OPEN_INVOICES_VIEW", apiVersion = "1")
    public ResponseEntity<CustomerOpenInvoicesPage> listCustomerOpenInvoices(
            @Parameter(description = "Customer identifier", example = "0198a000-0000-7000-8000-000000000412")
                    @PathVariable
                    UUID customerId,
            @Parameter(description = "Page index (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, 1 to 200", example = "100") @RequestParam(defaultValue = "100")
                    int size) {
        PageParameters.check(page, size, MAX_PAGE_SIZE);
        log.debug("Listing a customer's open invoices: page={}, size={}", page, size);
        return ResponseEntity.ok(receivablesWorklistService.listOpenInvoices(customerId, page, size));
    }
}
