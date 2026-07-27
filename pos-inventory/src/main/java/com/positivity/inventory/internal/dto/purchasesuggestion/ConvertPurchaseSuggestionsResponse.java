package com.positivity.inventory.internal.dto.purchasesuggestion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of converting accepted purchase suggestions into a DRAFT purchase order")
public class ConvertPurchaseSuggestionsResponse {

    @Schema(
            description = "Identifier of the created DRAFT purchase order",
            example = "01980003-0000-7000-8000-000000000005",
            requiredMode = REQUIRED)
    private UUID purchaseOrderId;

    @Schema(description = "Human-readable purchase order number", example = "PO-000123", requiredMode = REQUIRED)
    private String poNumber;

    @Schema(
            description =
                    "Status of the created purchase order (always DRAFT; the existing approval workflow" + " applies)",
            example = "DRAFT",
            requiredMode = REQUIRED)
    private String purchaseOrderStatus;

    @Schema(description = "Suggestions stamped CONVERTED by this call", requiredMode = REQUIRED)
    private List<UUID> convertedSuggestionIds;
}
