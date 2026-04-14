package com.positivity.workorder.internal.dto;

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
    @Schema(description = "Type of break")
    private BreakType breakType;

    @Nullable
    @Schema(description = "Optional break notes")
    private String notes;
}
