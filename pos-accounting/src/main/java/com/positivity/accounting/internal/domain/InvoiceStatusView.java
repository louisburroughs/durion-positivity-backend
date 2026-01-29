package com.positivity.accounting.internal.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Entity representing the current payment status of an invoice.
 * This is a denormalized view for quick status lookups.
 */
@Entity
@Table(name = "invoice_status_views")
public class InvoiceStatusView {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true)
    private String invoiceId;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus currentStatus;
    
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalPaid;
    
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal invoiceTotal;
    
    @Column(nullable = false)
    private Instant lastUpdated;
    
    @Column
    private String latestTransactionReference;
    
    // Constructors
    public InvoiceStatusView() {
    }
    
    public InvoiceStatusView(String invoiceId, PaymentStatus currentStatus, 
                            BigDecimal totalPaid, BigDecimal invoiceTotal) {
        this.invoiceId = invoiceId;
        this.currentStatus = currentStatus;
        this.totalPaid = totalPaid;
        this.invoiceTotal = invoiceTotal;
        this.lastUpdated = Instant.now();
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
    
    public PaymentStatus getCurrentStatus() {
        return currentStatus;
    }
    
    public void setCurrentStatus(PaymentStatus currentStatus) {
        this.currentStatus = currentStatus;
    }
    
    public BigDecimal getTotalPaid() {
        return totalPaid;
    }
    
    public void setTotalPaid(BigDecimal totalPaid) {
        this.totalPaid = totalPaid;
    }
    
    public BigDecimal getInvoiceTotal() {
        return invoiceTotal;
    }
    
    public void setInvoiceTotal(BigDecimal invoiceTotal) {
        this.invoiceTotal = invoiceTotal;
    }
    
    public Instant getLastUpdated() {
        return lastUpdated;
    }
    
    public void setLastUpdated(Instant lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
    
    public String getLatestTransactionReference() {
        return latestTransactionReference;
    }
    
    public void setLatestTransactionReference(String latestTransactionReference) {
        this.latestTransactionReference = latestTransactionReference;
    }
}
