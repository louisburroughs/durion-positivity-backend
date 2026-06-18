package com.positivity.workorder.internal.dto.pick;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.enums.ConsumeItemStatus;
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
@Schema(description = "Result of consuming picked items into a workorder")
public class ConsumePickedItemsResponse {

    @Schema(
            description = "Identifier of the workorder the items were consumed into",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID workorderId;

    @Schema(description = "Total number of items consumed", example = "2", requiredMode = REQUIRED)
    private int totalItemsConsumed;

    @Schema(description = "Per-item consumption results", requiredMode = NOT_REQUIRED)
    private List<ConsumedItemResult> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Outcome of consuming a single picked item")
    public static class ConsumedItemResult {

        @Schema(
                description = "Identifier of the pick task consumed",
                example = "01960003-0000-7000-8000-000000000002",
                requiredMode = REQUIRED)
        private UUID pickTaskId;

        @Schema(description = "Quantity actually consumed", example = "2", requiredMode = REQUIRED)
        private int quantityConsumed;

        @Schema(description = "Outcome status for the item", example = "SUCCESS", requiredMode = REQUIRED)
        private ConsumeItemStatus status;
    }
}
