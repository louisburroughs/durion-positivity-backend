package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * A location referenced from the employee register (durion#2155): the same small id-plus-name
 * shape {@link EmployeeJobRoleDto} already uses, so a register row can show a location label
 * without a second call to resolve it.
 *
 * <p>Backs {@code EmployeeSummaryDto.primaryLocation} only. DECISION-PEOPLE-004 is a single
 * primary location plus {@code EmployeeSummaryDto.otherLocationCount} for the rest -- the
 * register renders "Charlotte Main &middot; +1 more" -- never the full list of a person's
 * staffing assignments on the register row; the full list is what {@code
 * StaffingAssignmentController#findByPersonId} is for.
 */
@Value
@Builder
@Schema(description = "The employee's primary staffing location")
public class EmployeeLocationDto {

    @Schema(description = "Location id", requiredMode = Schema.RequiredMode.REQUIRED)
    UUID id;

    @Schema(
            description = "Display name, from the location replica; null when the replica has not caught up",
            example = "Charlotte Main",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    String name;
}
