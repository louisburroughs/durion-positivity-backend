package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.positivity.workorder.internal.enums.BreakType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for adding a break segment to a work session")
public class AddBreakSegmentRequest {

    @Nullable
    @Schema(description = "Type of break", example = "MEAL", requiredMode = NOT_REQUIRED)
    private BreakType breakType;

    @Nullable
    @Schema(description = "Optional break notes", example = "Lunch break", requiredMode = NOT_REQUIRED)
    private String notes;
}
