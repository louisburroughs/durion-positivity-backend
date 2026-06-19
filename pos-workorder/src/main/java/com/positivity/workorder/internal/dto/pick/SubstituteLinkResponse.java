package com.positivity.workorder.internal.dto.pick;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.workorder.internal.enums.SubstituteType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "A substitute-part link associating a product with an approved substitute")
public class SubstituteLinkResponse {
    @NotNull
    @Schema(
            description = "Identifier of the substitute link",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID id;

    @NotNull
    @Schema(
            description = "Identifier of the product the substitute applies to",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = REQUIRED)
    private UUID productId;

    @NotNull
    @Schema(
            description = "Identifier of the substitute part",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID substitutePartId;

    @NotNull
    @Schema(
            description = "Type of substitution relationship",
            example = "EQUIVALENT",
            requiredMode = REQUIRED)
    private SubstituteType substituteType;

    @Schema(
            description = "Ordering priority of this substitute among alternatives (lower is preferred)",
            example = "1",
            requiredMode = REQUIRED)
    private int priority;

    @JsonProperty("isAutoSuggest")
    @Schema(
            description = "Whether this substitute is automatically suggested during picking",
            example = "true",
            requiredMode = REQUIRED)
    private boolean isAutoSuggest;

    @JsonProperty("isActive")
    @Schema(
            description = "Whether this substitute link is currently active",
            example = "true",
            requiredMode = REQUIRED)
    private boolean isActive;

    @Schema(
            description = "Version for optimistic locking",
            example = "0",
            requiredMode = REQUIRED)
    private int version;

    @Schema(
            description = "Identifier of the user who created the link",
            example = "system",
            requiredMode = NOT_REQUIRED)
    private String createdBy;

    @Schema(
            description = "Identifier of the user who last updated the link",
            example = "system",
            requiredMode = NOT_REQUIRED)
    private String updatedBy;
}
