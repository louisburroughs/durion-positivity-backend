package com.positivity.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response payload for invoice generation from workorder.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceGenerationResponse {

    private UUID invoiceId;
    private String status;
    private UUID workorderId;
    private UUID estimateId;
    private UUID approvalId;
    private BigDecimal subtotal;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private Instant createdAt;
}
