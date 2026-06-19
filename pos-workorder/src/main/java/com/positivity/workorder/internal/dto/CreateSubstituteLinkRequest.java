package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.enums.SubstituteType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload to create a substitute part link")
public class CreateSubstituteLinkRequest {

    @NotNull
    @Schema(
            description = "Primary product identifier the substitute applies to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID productId;

    @NotNull
    @Schema(
            description = "Substitute part identifier offered in place of the primary product",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    private UUID substitutePartId;

    @NotNull
    @Schema(description = "Type of substitution relationship", example = "EQUIVALENT", requiredMode = REQUIRED)
    private SubstituteType substituteType;

    @PositiveOrZero
    @Schema(
            description = "Ordering priority for the substitute suggestion (lower is preferred)",
            example = "2",
            requiredMode = NOT_REQUIRED)
    private Integer priority;

    @Schema(
            description = "Whether this substitute is offered automatically as a suggestion",
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
}
