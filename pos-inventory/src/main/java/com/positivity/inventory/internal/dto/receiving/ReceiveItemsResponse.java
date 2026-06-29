package com.positivity.inventory.internal.dto.receiving;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Result of recording received quantities for a receiving session")
public class ReceiveItemsResponse {
    @Schema(
            description = "Identifier of the receiving session the items were recorded against",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID sessionId;

    @Schema(
            description = "Status of the receiving session after recording the items",
            example = "IN_PROGRESS",
            requiredMode = REQUIRED)
    @NotNull
    private String sessionStatus;

    @Schema(description = "Number of receiving lines processed in this request", example = "3", requiredMode = REQUIRED)
    private int linesProcessed;

    @Schema(
            description = "Variance summaries for lines whose received quantity differed from the expected quantity",
            requiredMode = NOT_REQUIRED)
    private List<VarianceSummaryResponse> variances;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Summary of a quantity variance detected on a single receiving line")
    public static class VarianceSummaryResponse {
        @Schema(
                description = "Identifier of the receiving line that had a variance",
                example = "01960003-0000-7000-8000-000000000002",
                requiredMode = REQUIRED)
        @NotNull
        private UUID lineId;

        @Schema(
                description = "Identifier of the product on the receiving line",
                example = "01960003-0000-7000-8000-000000000003",
                requiredMode = REQUIRED)
        @NotNull
        private String productId;

        @Schema(
                description = "Type of variance detected, such as OVER or SHORT",
                example = "SHORT",
                requiredMode = REQUIRED)
        @NotNull
        private String varianceType;

        @Schema(
                description = "Difference between received and expected quantity for the line",
                example = "2",
                requiredMode = REQUIRED)
        @NotNull
        private BigDecimal varianceQuantity;

        @Schema(
                description = "Quantity originally expected on the receiving line",
                example = "10",
                requiredMode = REQUIRED)
        @NotNull
        private BigDecimal expectedQuantity;

        @Schema(description = "Quantity actually received on the line", example = "8", requiredMode = REQUIRED)
        @NotNull
        private BigDecimal receivedQuantity;
    }
}
