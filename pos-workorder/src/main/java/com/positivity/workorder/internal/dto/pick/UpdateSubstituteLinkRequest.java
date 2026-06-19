package com.positivity.workorder.internal.dto.pick;

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
@Schema(description = "Partial update request for a substitute SKU link on a pick task")
public class UpdateSubstituteLinkRequest {

    @Schema(description = "Type of substitution relationship", example = "EQUIVALENT", requiredMode = NOT_REQUIRED)
    private SubstituteType substituteType;

    @Schema(description = "Suggestion priority ordering for the substitute", example = "2", requiredMode = NOT_REQUIRED)
    private Integer priority;

    @Schema(
            description = "Whether the substitute is auto-suggested to pickers",
            example = "true",
            requiredMode = NOT_REQUIRED)
    private Boolean isAutoSuggest;

    @Schema(
            description = "Timestamp from which the substitute link is effective",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant effectiveFrom;

    @Schema(
            description = "Timestamp at which the substitute link stops being effective",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant effectiveTo;

    @Schema(
            description = "Optimistic-locking version of the substitute link",
            example = "2",
            requiredMode = NOT_REQUIRED)
    private int version;
}
