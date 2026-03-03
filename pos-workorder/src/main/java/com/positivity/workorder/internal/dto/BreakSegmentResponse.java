package com.positivity.workorder.internal.dto;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.positivity.workorder.internal.enums.BreakType;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response DTO for a break segment")
public class BreakSegmentResponse {

    @Schema(description = "Unique identifier of the break segment")
    private UUID breakSegmentId;

    @Schema(description = "ID of the parent work session")
    private UUID workSessionId;

    @Schema(description = "Break start timestamp")
    private Instant breakStartAt;

    @Nullable
    @Schema(description = "Break end timestamp")
    private Instant breakEndAt;

    @Nullable
    @Schema(description = "Type of break")
    private BreakType breakType;

    @Nullable
    @Schema(description = "Optional break notes")
    private String notes;
}
