package com.positivity.inventory.internal.dto.consumption;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(
        description =
                "Request payload to consume inventory items against a workorder, optionally drawn from a specific pick list")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsumeItemsRequest {

    @Schema(
            description = "Identifier of the workorder the items are consumed for",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(
            description = "Identifier of the pick list the consumed items are drawn from, if applicable",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID pickListId;

    @Schema(description = "Line items identifying the SKUs and quantities to consume", requiredMode = NOT_REQUIRED)
    private List<@Valid ConsumeItemLine> items;
}
