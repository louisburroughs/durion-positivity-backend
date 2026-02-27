package com.positivity.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Internal service-to-service payload for creating invoice drafts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceCreationRequest {

    private UUID workorderId;

    private UUID estimateId;

    private UUID approvalId;

    private String idempotencyKey;

    private List<InvoiceLineItem> lineItems;
}
