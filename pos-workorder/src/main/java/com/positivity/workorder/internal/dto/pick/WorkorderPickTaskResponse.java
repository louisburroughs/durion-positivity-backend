package com.positivity.workorder.internal.dto.pick;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response describing a single pick task within a pick list")
public class WorkorderPickTaskResponse {

    @Schema(
            description = "Pick task identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID pickTaskId;

    @Schema(
            description = "Identifier of the pick list the task belongs to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID pickListId;

    @Schema(
            description = "Identifier of the SKU to pick",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID skuId;

    @Schema(
            description = "Identifier of the location to pick from",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID locationId;

    @Schema(description = "Quantity required to pick", example = "2", requiredMode = REQUIRED)
    private int requiredQty;

    @Schema(description = "Quantity already picked", example = "2", requiredMode = REQUIRED)
    private int pickedQty;

    @Schema(description = "Quantity remaining to pick", example = "2", requiredMode = REQUIRED)
    private int remainingQty;

    @Schema(description = "Status of the pick task", example = "OPEN", requiredMode = REQUIRED)
    private String status;

    @Schema(description = "Sort order of the pick task within the list", example = "2", requiredMode = REQUIRED)
    private int sortOrder;

    @Schema(description = "Optimistic-locking version of the pick task", example = "2", requiredMode = REQUIRED)
    private long version;
}
