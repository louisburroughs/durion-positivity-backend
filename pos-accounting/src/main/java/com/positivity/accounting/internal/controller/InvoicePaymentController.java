package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.BillingRuleRefResponse;
import com.positivity.accounting.internal.dto.InvoiceStatusResponse;
import com.positivity.accounting.internal.dto.RegenerateInvoiceFromWorkorderRequest;
import com.positivity.accounting.service.BillingRulesService;
import com.positivity.accounting.service.InvoicePaymentStatusService;
import com.positivity.accounting.service.InvoiceRegenerationService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for invoice payment operations.
 * Provides endpoints for applying payments and querying invoice status.
 */
@RestController
@Tag(name = "Invoice Payments", description = "Apply payments and query invoice status.")
@SecurityRequirement(name = "bearerAuth", scopes = { "accounting:ap:view" })
@Validated
public class InvoicePaymentController {

    private static final Logger log = LoggerFactory.getLogger(InvoicePaymentController.class);

    private final InvoicePaymentStatusService paymentStatusService;
    private final BillingRulesService billingRulesService;
    private final InvoiceRegenerationService invoiceRegenerationService;

    public InvoicePaymentController(
            InvoicePaymentStatusService paymentStatusService,
            BillingRulesService billingRulesService,
            InvoiceRegenerationService invoiceRegenerationService) {
        this.paymentStatusService = paymentStatusService;
        this.billingRulesService = billingRulesService;
        this.invoiceRegenerationService = invoiceRegenerationService;
    }

    /**
     * Get current payment status of an invoice.
     *
     * @param invoiceId Invoice identifier
     * @return Current invoice status
     */
    @GetMapping("/v1/accounting/invoice/{invoiceId}/status")
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    @Operation(summary = "Get invoice status", description = "Retrieve current payment status for an invoice.", tags = {
            "Invoice Payments" })
    @ApiResponse(responseCode = "200", description = "Invoice status returned", content = @Content(schema = @Schema(implementation = InvoiceStatusResponse.class)))
    @ApiResponse(responseCode = "404", description = "Invoice not found")
    @ApiResponse(responseCode = "500", description = "Error retrieving invoice status")
    public ResponseEntity<InvoiceStatusResponse> getInvoiceStatus(
            @Parameter(description = "Invoice identifier") @PathVariable UUID invoiceId) {

        try {
            InvoiceStatusResponse response = paymentStatusService.getInvoiceStatus(invoiceId);
            return ResponseEntity.ok(response);

        } catch (EntityNotFoundException _) {
            log.error("Invoice not found: {}", invoiceId);
            return ResponseEntity.notFound().build();

        } catch (IllegalArgumentException e) {
            log.warn("Invalid request for invoice status {}: {}", invoiceId, e.getMessage());
            return ResponseEntity.badRequest().build();

        } catch (Exception e) {
            log.error("Error retrieving status for invoice {}: {}", invoiceId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/v1/accounting/invoice/invoices")
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    @Operation(summary = "Regenerate invoice from workorder", description = "Regenerate an invoice from a workorder.", tags = {
            "Invoice Payments" })
    @ApiResponse(responseCode = "200", description = "Invoice regenerated")
    @ApiResponse(responseCode = "404", description = "Workorder not found")
    @ApiResponse(responseCode = "409", description = "Workorder is not in COMPLETED state")
    @ApiResponse(responseCode = "503", description = "Workorder service unavailable")
    @EmitEvent(id = "ACCOUNTING_INVOICE_REGENERATE", apiVersion = "1")
    public ResponseEntity<InvoiceGenerationResponse> regenerateInvoiceFromWorkorder(
            @Valid @RequestBody RegenerateInvoiceFromWorkorderRequest request) {

        InvoiceGenerationResponse response = invoiceRegenerationService.regenerateInvoiceFromWorkorder(
                request.getWorkorderId(), request.getIdempotencyKey());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/v1/accounting/invoice/rules/{customerId}")
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    @Operation(summary = "Get billing rules", description = "Retrieve billing rules for a customer.", tags = {
            "Invoice Payments" })
    @ApiResponse(responseCode = "200", description = "Billing rules returned")
    @ApiResponse(responseCode = "404", description = "Customer not found")
    @ApiResponse(responseCode = "503", description = "Customer service unavailable")
    public ResponseEntity<BillingRuleRefResponse> getBillingRules(
            @Parameter(description = "Customer identifier") @PathVariable UUID customerId) {
        log.info("Fetching billing rules for customer {}", customerId);
        BillingRuleRefResponse rules = billingRulesService.getBillingRules(customerId);
        return ResponseEntity.ok(rules);
    }
}
