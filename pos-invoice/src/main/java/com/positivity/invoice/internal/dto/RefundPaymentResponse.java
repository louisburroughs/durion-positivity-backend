package com.positivity.invoice.internal.dto;

import com.positivity.invoice.internal.enums.RefundReason;
import com.positivity.invoice.internal.enums.RefundStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
public class RefundPaymentResponse {
    private UUID refundId;
    private UUID invoiceId;
    private UUID paymentIntentId;
    private BigDecimal amount;
    private RefundReason reason;
    private String notes;
    private RefundStatus status;
    private String gatewayReference;
    private Instant completedAt;
}