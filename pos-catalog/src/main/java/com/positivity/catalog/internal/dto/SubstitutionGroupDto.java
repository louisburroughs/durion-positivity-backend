package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "A substitution group: a set of mutually interchangeable products")
public class SubstitutionGroupDto {

    @Schema(
            description = "Identifier of the substitution group",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID id;

    @Schema(description = "Group name", example = "5W-30 full synthetic quarts", requiredMode = REQUIRED)
    private String name;

    @Schema(description = "Free-text notes", example = "Any brand acceptable", requiredMode = NOT_REQUIRED)
    private String notes;

    @Schema(description = "Product ids of all group members", requiredMode = REQUIRED)
    private List<UUID> productIds;

    @Schema(description = "Created timestamp", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(description = "Updated timestamp", example = "2026-01-16T11:00:00Z", requiredMode = NOT_REQUIRED)
    private Instant updatedAt;
}
