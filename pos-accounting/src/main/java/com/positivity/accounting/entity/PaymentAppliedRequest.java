package com.positivity.accounting.entity;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * DTO for payment applied request.
 */
public class PaymentAppliedRequest {

    @NotBlank(message = "Invoice ID is required")
    private String invoiceId;

    @NotBlank(message = "Transaction reference is required")
    private String transactionReference;

    @NotNull(message = "Payment amount is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Payment amount must be positive")
    private BigDecimal paymentAmount;

    @NotNull(message = "Invoice total is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Invoice total must be positive")
    private BigDecimal invoiceTotal;

    @NotBlank(message = "Idempotency key is required")
    private String idempotencyKey;

    private boolean paymentFailed = false;

    // Constructors
    public PaymentAppliedRequest() {
    }

    // Getters and Setters
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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public boolean isPaymentFailed() {
        return paymentFailed;
    }

    public void setPaymentFailed(boolean paymentFailed) {
        this.paymentFailed = paymentFailed;
    }
}
