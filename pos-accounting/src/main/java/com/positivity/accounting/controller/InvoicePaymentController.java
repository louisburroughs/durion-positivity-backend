package com.positivity.accounting.controller;

import com.positivity.accounting.dto.InvoiceStatusResponse;
import com.positivity.accounting.dto.PaymentAppliedRequest;
import com.positivity.accounting.service.InvoicePaymentStatusService;
import com.positivity.events.EmitEvent;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for invoice payment operations.
 * Provides endpoints for applying payments and querying invoice status.
 */
@RestController
@RequestMapping("/api/accounting")
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
    @PostMapping("/payment-applied")
    @EmitEvent(id = "payment.applied")
    public ResponseEntity<InvoiceStatusResponse> applyPayment(
            @Valid @RequestBody PaymentAppliedRequest request) {
        
        log.info("Received payment application for invoice {}", request.getInvoiceId());
        
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
    @GetMapping("/invoice/{invoiceId}/status")
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
}
