package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "An effective-dated role assignment binding a user to a role")
public class RoleAssignmentDto {
    @Schema(
            description = "Role assignment identifier",
            example = "01960003-0000-7000-8000-000000000020",
            requiredMode = REQUIRED)
    UUID id;

    @Schema(
            description = "Identifier of the user the role is assigned to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    UUID userId;

    @Schema(
            description = "Identifier of the assigned role",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    UUID roleId;

    @Schema(
            description = "Inclusive start of the effective window",
            example = "2026-01-15T00:00:00",
            requiredMode = NOT_REQUIRED)
    LocalDateTime effectiveStartDate;

    @Schema(
            description = "Exclusive end of the effective window",
            example = "2026-12-31T00:00:00",
            requiredMode = NOT_REQUIRED)
    LocalDateTime effectiveEndDate;

    @Schema(
            description = "Timestamp at which the assignment was revoked",
            example = "2026-06-01T12:00:00Z",
            requiredMode = NOT_REQUIRED)
    Instant revokedAt;

    @Schema(description = "Creation timestamp", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
    Instant createdAt;

    @Schema(description = "Actor that created the assignment", example = "system", requiredMode = NOT_REQUIRED)
    String createdBy;

    @Schema(description = "Last-modified timestamp", example = "2026-01-16T11:00:00Z", requiredMode = NOT_REQUIRED)
    Instant lastModifiedAt;

    @Schema(description = "Actor that last modified the assignment", example = "jane.doe", requiredMode = NOT_REQUIRED)
    String lastModifiedBy;
}
