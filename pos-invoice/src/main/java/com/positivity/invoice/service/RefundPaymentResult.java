package com.positivity.invoice.service;

import com.positivity.invoice.internal.enums.RefundReason;
import com.positivity.invoice.internal.enums.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
public class RefundPaymentResult {
    private UUID refundId;
    private UUID invoiceId;
    private UUID paymentIntentId;
    private BigDecimal amount;
    private RefundReason reason;
    private String notes;
    private RefundStatus status;
    private String gatewayReference;
    private String externalReference;
    private Instant completedAt;
}
