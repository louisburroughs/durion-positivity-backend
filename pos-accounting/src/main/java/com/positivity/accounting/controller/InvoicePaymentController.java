package com.positivity.accounting.controller;

import com.positivity.accounting.entity.InvoiceStatusResponse;
import com.positivity.accounting.entity.PaymentAppliedRequest;
import com.positivity.accounting.service.InvoicePaymentStatusService;
import com.positivity.events.EmitEvent;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for invoice payment operations.
 * Provides endpoints for applying payments and querying invoice status.
 */
@RestController
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
    @EmitEvent(id = "payment.applied")
    public ResponseEntity<InvoiceStatusResponse> applyPayment(
            @PathVariable String paymentId,
            @Valid @RequestBody PaymentAppliedRequest request) {

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
    public ResponseEntity<InvoiceStatusResponse> getInvoiceStatus(
            @PathVariable String invoiceId) {

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
    public ResponseEntity<Void> regenerateInvoiceFromWorkorder(@RequestBody(required = false) Object body) {
        log.info("Stub regenerateInvoiceFromWorkorder");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/v1/invoice/rules/{customerId}")
    @PreAuthorize("hasAuthority('accounting:ap:view')")
    public ResponseEntity<Void> getBillingRules(@PathVariable String customerId) {
        log.info("Stub getBillingRules customerId={}", customerId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
