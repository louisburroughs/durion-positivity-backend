package com.positivity.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Internal service-to-service payload for creating invoice drafts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Internal service payload for creating invoice drafts.")
public class InvoiceCreationRequest {

    @Schema(
            description = "Workorder identifier backing the invoice draft.",
            example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID workorderId;

    @Schema(
            description = "Source estimate identifier, when generated from estimate.",
            example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID estimateId;

    @Schema(
            description = "Approval identifier used for billing authorization.",
            example = "550e8400-e29b-41d4-a716-446655440002")
    private UUID approvalId;

    @Schema(description = "Idempotency key to prevent duplicate invoice creation.", example = "inv-create-wo-1234")
    private String idempotencyKey;

    @Schema(description = "Line items to include on the invoice.")
    private List<InvoiceLineItem> lineItems;
}
