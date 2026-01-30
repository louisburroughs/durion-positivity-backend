package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.entity.InvoiceStatusResponse;
import com.positivity.accounting.internal.entity.PaymentAppliedRequest;
import com.positivity.accounting.service.InvoicePaymentStatusService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
     * Apply a payment to an invoice and update status.
     * 
     * @param request Payment details including idempotency key
     * @return Updated invoice status
     */
    @PostMapping("/v1/accounting/payments/{paymentId}/applications")
    @PreAuthorize("hasAuthority('accounting:ap:pay')")
    @Operation(summary = "Apply payment", description = "Apply a payment to an invoice and update its status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payment applied", content = @Content(schema = @Schema(implementation = InvoiceStatusResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid payment request"),
            @ApiResponse(responseCode = "500", description = "Processing error")
    })
    public ResponseEntity<InvoiceStatusResponse> applyPayment(
            @Parameter(description = "Payment identifier") @PathVariable String paymentId,
            @Valid @RequestBody @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Payment application payload", required = true, content = @Content(schema = @Schema(implementation = PaymentAppliedRequest.class))) PaymentAppliedRequest request) {

        log.info("Received payment application for invoice {}", request.getInvoiceId());

        if (!paymentId.equals(request.getTransactionReference())) {
            log.warn(
                    "paymentId path param does not match transactionReference in body (paymentId={}, transactionReference={})",
                    paymentId,
                    request.getTransactionReference());
            return ResponseEntity.badRequest().build();
        }

        try {
            InvoiceStatusResponse response = paymentStatusService.processPaymentApplied(request);
            log.info("Successfully processed payment for invoice {} - status: {}",
                    request.getInvoiceId(), response.getStatus());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error processing payment for invoice {}: {}",
                    request.getInvoiceId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
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
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice status returned", content = @Content(schema = @Schema(implementation = InvoiceStatusResponse.class))),
            @ApiResponse(responseCode = "404", description = "Invoice not found"),
            @ApiResponse(responseCode = "500", description = "Error retrieving invoice status")
    })
    public ResponseEntity<InvoiceStatusResponse> getInvoiceStatus(
            @Parameter(description = "Invoice identifier") @PathVariable String invoiceId) {

        log.info("Querying status for invoice {}", invoiceId);

        try {
            InvoiceStatusResponse response = paymentStatusService.getInvoiceStatus(invoiceId);
            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            log.error("Invoice not found: {}", invoiceId);
            return ResponseEntity.notFound().build();

        } catch (Exception e) {
            log.error("Error retrieving status for invoice {}: {}",
                    invoiceId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/v1/invoice/invoices")
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    @Operation(summary = "Regenerate invoice from workorder", description = "Regenerate an invoice from a workorder.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Invoice regeneration accepted"),
            @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    public ResponseEntity<Void> regenerateInvoiceFromWorkorder(@RequestBody(required = false) Object body) {
        log.info("Stub regenerateInvoiceFromWorkorder");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/v1/invoice/rules/{customerId}")
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    @Operation(summary = "Get billing rules", description = "Retrieve billing rules for a customer.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Billing rules returned"),
            @ApiResponse(responseCode = "404", description = "Customer not found")
    })
    public ResponseEntity<Void> getBillingRules(
            @Parameter(description = "Customer identifier") @PathVariable String customerId) {
        log.info("Stub getBillingRules customerId={}", customerId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
