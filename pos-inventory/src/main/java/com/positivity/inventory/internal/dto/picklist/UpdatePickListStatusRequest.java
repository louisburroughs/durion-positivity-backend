package com.positivity.inventory.internal.dto.picklist;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.PickListStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Request to transition a pick list to a new lifecycle status")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePickListStatusRequest {

    @Schema(
            description = "Target status to apply to the pick list",
            example = "IN_PROGRESS",
            requiredMode = REQUIRED)
    @NotNull
    private PickListStatus status;
}
