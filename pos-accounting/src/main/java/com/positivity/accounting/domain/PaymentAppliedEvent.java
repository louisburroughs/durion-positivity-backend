package com.positivity.accounting.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Entity representing a payment applied to an invoice.
 * Stores payment transaction details for auditing and status calculation.
 */
@Entity
@Table(name = "payment_applied_events")
public class PaymentAppliedEvent {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String invoiceId;
    
    @Column(nullable = false)
    private String transactionReference;
    
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal paymentAmount;
    
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal invoiceTotal;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;
    
    @Column(nullable = false)
    private Instant timestamp;
    
    @Column
    private String idempotencyKey;
    
    // Constructors
    public PaymentAppliedEvent() {
    }
    
    public PaymentAppliedEvent(String invoiceId, String transactionReference, 
                               BigDecimal paymentAmount, BigDecimal invoiceTotal, 
                               PaymentStatus status, String idempotencyKey) {
        this.invoiceId = invoiceId;
        this.transactionReference = transactionReference;
        this.paymentAmount = paymentAmount;
        this.invoiceTotal = invoiceTotal;
        this.status = status;
        this.timestamp = Instant.now();
        this.idempotencyKey = idempotencyKey;
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getInvoiceId() {
        return invoiceId;
    }
    
    public void setInvoiceId(String invoiceId) {
        this.invoiceId = invoiceId;
    }
    
    public String getTransactionReference() {
        return transactionReference;
    }
    
    public void setTransactionReference(String transactionReference) {
        this.transactionReference = transactionReference;
    }
    
    public BigDecimal getPaymentAmount() {
        return paymentAmount;
    }
    
    public void setPaymentAmount(BigDecimal paymentAmount) {
        this.paymentAmount = paymentAmount;
    }
    
    public BigDecimal getInvoiceTotal() {
        return invoiceTotal;
    }
    
    public void setInvoiceTotal(BigDecimal invoiceTotal) {
        this.invoiceTotal = invoiceTotal;
    }
    
    public PaymentStatus getStatus() {
        return status;
    }
    
    public void setStatus(PaymentStatus status) {
        this.status = status;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
    
    public String getIdempotencyKey() {
        return idempotencyKey;
    }
    
    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
