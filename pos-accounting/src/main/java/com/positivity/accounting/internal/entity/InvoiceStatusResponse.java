package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO for invoice status response.
 */
public class InvoiceStatusResponse {

    private String invoiceId;
    private PaymentStatus status;
    private BigDecimal totalPaid;
    private BigDecimal invoiceTotal;
    private BigDecimal remainingBalance;
    private String latestTransactionReference;
    private Instant lastUpdated;

    // Constructors
    public InvoiceStatusResponse() {
    }

    public InvoiceStatusResponse(String invoiceId, PaymentStatus status,
            BigDecimal totalPaid, BigDecimal invoiceTotal,
            String latestTransactionReference, Instant lastUpdated) {
        this.invoiceId = invoiceId;
        this.status = status;
        this.totalPaid = totalPaid;
        this.invoiceTotal = invoiceTotal;
        this.remainingBalance = invoiceTotal.subtract(totalPaid);
        this.latestTransactionReference = latestTransactionReference;
        this.lastUpdated = lastUpdated;
    }

    // Getters and Setters
    public String getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(String invoiceId) {
        this.invoiceId = invoiceId;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
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

    public BigDecimal getRemainingBalance() {
        return remainingBalance;
    }

    public void setRemainingBalance(BigDecimal remainingBalance) {
        this.remainingBalance = remainingBalance;
    }

    public String getLatestTransactionReference() {
        return latestTransactionReference;
    }

    public void setLatestTransactionReference(String latestTransactionReference) {
        this.latestTransactionReference = latestTransactionReference;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Instant lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
}
