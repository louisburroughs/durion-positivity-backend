package com.positivity.shopmanager.internal.service.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.shopmanager.internal.service.enums.MechanicRole;
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
            description = "The mechanic's People-domain person id (ADR-0015 §7 I7) - the same value the request "
                    + "carries as mechanicPersonId. Use this to identify the mechanic across services.",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    UUID mechanicPersonId;

    @Schema(
            description = "Internal shop-manager mechanic record id (a local surrogate key). Not a person id and "
                    + "not a stable cross-service identifier; do not send it to other services.",
            example = "01960003-0000-7000-8000-000000000011",
            requiredMode = REQUIRED)
    UUID mechanicRecordId;

    @Schema(description = "Role of the mechanic in the assignment", example = "LEAD", requiredMode = REQUIRED)
    MechanicRole role;
}
