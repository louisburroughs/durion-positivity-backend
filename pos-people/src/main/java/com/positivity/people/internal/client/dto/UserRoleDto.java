package com.positivity.people.internal.client.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "User role assignment as exposed by the security service")
public class UserRoleDto {

    @Schema(
            description = "User identifier",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private String userId;

    @Schema(description = "Stable role code", example = "TECHNICIAN", requiredMode = NOT_REQUIRED)
    private String roleCode;

    @Schema(
            description = "Location identifier the role is scoped to",
            example = "01960011-0000-7000-8000-000000000010",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(description = "Date the assignment becomes effective", example = "2026-01-01", requiredMode = NOT_REQUIRED)
    private LocalDate startDate;

    @Schema(description = "Date the assignment ends", example = "2026-12-31", requiredMode = NOT_REQUIRED)
    private LocalDate endDate;

    @Schema(description = "Whether the assignment is active", example = "true", requiredMode = NOT_REQUIRED)
    private Boolean active;
}
