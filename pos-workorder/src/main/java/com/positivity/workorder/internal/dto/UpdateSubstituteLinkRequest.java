package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.positivity.workorder.internal.enums.SubstituteType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for partial update of a substitute part link")
public class UpdateSubstituteLinkRequest {

    @Schema(
            description = "Updated classification of the substitute relationship",
            example = "EQUIVALENT",
            requiredMode = NOT_REQUIRED)
    private SubstituteType substituteType;

    @Schema(description = "Updated ranking priority for the substitute", example = "2", requiredMode = NOT_REQUIRED)
    private Integer priority;

    @Schema(
            description = "Whether the substitute should be auto-suggested",
            example = "true",
            requiredMode = NOT_REQUIRED)
    private Boolean isAutoSuggest;

    @Schema(
            description = "Timestamp from which the substitute link is effective",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant effectiveFrom;

    @Schema(
            description = "Timestamp after which the substitute link is no longer effective",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant effectiveTo;

    @Schema(
            description = "Optimistic-locking version of the substitute link being updated",
            example = "2",
            requiredMode = NOT_REQUIRED)
    private int version;
}
