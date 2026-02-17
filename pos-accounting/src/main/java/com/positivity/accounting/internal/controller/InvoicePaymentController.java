package com.positivity.accounting.internal.controller;

import java.util.UUID;

import jakarta.persistence.EntityNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.accounting.internal.dto.InvoiceStatusResponse;
import com.positivity.accounting.service.InvoicePaymentStatusService;
import com.positivity.events.EmitEvent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST Controller for invoice payment operations.
 * Provides endpoints for applying payments and querying invoice status.
 */
@RestController
@Tag(name = "Invoice Payments", description = "Apply payments and query invoice status.")
public class InvoicePaymentController {

    private static final Logger log = LoggerFactory.getLogger(InvoicePaymentController.class);

    private final InvoicePaymentStatusService paymentStatusService;

    public InvoicePaymentController(InvoicePaymentStatusService paymentStatusService) {
        this.paymentStatusService = paymentStatusService;
    }

    /**
     * Get current payment status of an invoice.
     * 
     * @param invoiceId Invoice identifier
     * @return Current invoice status
     */
    @GetMapping("/v1/accounting/invoice/{invoiceId}/status")
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    @Operation(summary = "Get invoice status", description = "Retrieve current payment status for an invoice.")
    @ApiResponse(responseCode = "200", description = "Invoice status returned", content = @Content(schema = @Schema(implementation = InvoiceStatusResponse.class)))
    @ApiResponse(responseCode = "404", description = "Invoice not found")
    @ApiResponse(responseCode = "500", description = "Error retrieving invoice status")
    public ResponseEntity<InvoiceStatusResponse> getInvoiceStatus(
            @Parameter(description = "Invoice identifier") @PathVariable UUID invoiceId) {

        log.info("Querying status for invoice {}", invoiceId);

        try {
            InvoiceStatusResponse response = paymentStatusService.getInvoiceStatus(invoiceId);
            return ResponseEntity.ok(response);

        } catch (EntityNotFoundException e) {
            log.error("Invoice not found: {}", invoiceId);
            return ResponseEntity.notFound().build();

        } catch (IllegalArgumentException e) {
            log.warn("Invalid request for invoice status {}: {}", invoiceId, e.getMessage());
            return ResponseEntity.badRequest().build();

        } catch (Exception e) {
            log.error("Error retrieving status for invoice {}: {}",
                    invoiceId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/v1/accounting/invoice/invoices")
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    @Operation(summary = "Regenerate invoice from workorder", description = "Regenerate an invoice from a workorder.")
    @ApiResponse(responseCode = "501", description = "Not implemented")
    @EmitEvent(id = "ACCOUNTING_INVOICE_REGENERATE", apiVersion = "1")
    public ResponseEntity<Void> regenerateInvoiceFromWorkorder(@RequestBody(required = false) Object body) {
        log.info("Stub regenerateInvoiceFromWorkorder");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/v1/accounting/invoice/rules/{customerId}")
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    @Operation(summary = "Get billing rules", description = "Retrieve billing rules for a customer.")
    @ApiResponse(responseCode = "501", description = "Not implemented")
    public ResponseEntity<Void> getBillingRules(
            @Parameter(description = "Customer identifier") @PathVariable UUID customerId) {
        log.info("Stub getBillingRules customerId={}", customerId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
