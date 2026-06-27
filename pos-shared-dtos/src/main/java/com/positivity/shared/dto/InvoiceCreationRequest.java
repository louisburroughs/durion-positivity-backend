package com.positivity.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
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
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID workorderId;

    @Schema(
            description = "Source estimate identifier, when generated from estimate.",
            example = "550e8400-e29b-41d4-a716-446655440001",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID estimateId;

    @Schema(
            description = "Approval identifier used for billing authorization.",
            example = "550e8400-e29b-41d4-a716-446655440002",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID approvalId;

    @Schema(
            description = "Shop location where the sale is made; used to resolve the tax jurisdiction address.",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Idempotency key to prevent duplicate invoice creation.",
            example = "inv-create-wo-1234",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String idempotencyKey;

    @Valid
    @Schema(description = "Line items to include on the invoice.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private List<InvoiceLineItem> lineItems;
}
