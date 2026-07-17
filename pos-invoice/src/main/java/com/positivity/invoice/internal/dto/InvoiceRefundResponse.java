package com.positivity.invoice.internal.dto;

import com.positivity.invoice.internal.enums.RefundReason;
import com.positivity.invoice.internal.enums.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

/**
 * One refund record anchored to an invoice, as returned by
 * {@code GET /v1/invoices/{invoiceId}/refunds} (#922 warranty settlement reconciliation).
 *
 * <p>Field names are the reconciliation contract consumed by pos-warranty:
 * {@code id, paymentIntentId, amount, status, reason, externalReference, gatewayReference,
 * requestedAt, completedAt}. {@code paymentIntentId} and {@code gatewayReference} are null for
 * standalone refunds (no gateway leg, #926).
 */
@Data
public class InvoiceRefundResponse {
    private UUID id;
    private UUID paymentIntentId;
    private BigDecimal amount;
    private RefundStatus status;
    private RefundReason reason;
    private String externalReference;
    private String gatewayReference;
    private Instant requestedAt;
    private Instant completedAt;
}
