package com.positivity.shopmanager.service.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.shopmanager.service.enums.MechanicRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/** Summary of one mechanic included in an assignment response. */
@Value
@Builder
@Schema(description = "Summary of one mechanic included in an assignment response")
public class AssignedMechanicInfo {
    @Schema(
            description = "Mechanic identifier",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    UUID mechanicId;

    @Schema(description = "Role of the mechanic in the assignment", example = "LEAD", requiredMode = REQUIRED)
    MechanicRole role;
}
