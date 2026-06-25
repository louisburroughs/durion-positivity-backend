package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Response describing a link between a user account and a person record")
public class UserPersonLinkResponse {

    @Schema(
            description = "Link identifier",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID linkId;

    @Schema(
            description = "User account identifier",
            example = "01960011-0000-7000-8000-000000000002",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID userId;

    @Schema(
            description = "Person identifier",
            example = "01960011-0000-7000-8000-000000000003",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @Schema(
            description = "Type of link between user and person",
            example = "PRIMARY",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String linkType;

    @Schema(
            description = "Creation timestamp in UTC",
            example = "2026-01-15T09:30:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Identifier of the actor that created the link",
            example = "admin.user",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String createdBy;

    @Schema(
            description = "Optional notes describing the link",
            example = "Linked during onboarding",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String notes;
}
