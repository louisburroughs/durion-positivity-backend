package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.enums.BreakType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response DTO for a break segment")
public class BreakSegmentResponse {

    @NotNull
    @Schema(
            description = "Unique identifier of the break segment",
            example = "550e8400-e29b-41d4-a716-446655440400",
            requiredMode = REQUIRED)
    private UUID breakSegmentId;

    @NotNull
    @Schema(
            description = "ID of the parent work session",
            example = "550e8400-e29b-41d4-a716-446655440401",
            requiredMode = REQUIRED)
    private UUID workSessionId;

    @NotNull
    @Schema(description = "Break start timestamp", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    private Instant breakStartAt;

    @Nullable
    @Schema(description = "Break end timestamp", example = "2026-01-15T10:00:00Z", requiredMode = NOT_REQUIRED)
    private Instant breakEndAt;

    @Nullable
    @Schema(description = "Type of break", example = "MEAL", requiredMode = NOT_REQUIRED)
    private BreakType breakType;

    @Nullable
    @Schema(description = "Optional break notes", example = "Lunch break", requiredMode = NOT_REQUIRED)
    private String notes;
}
