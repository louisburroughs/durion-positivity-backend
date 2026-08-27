package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.TimePeriodStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to move a pay period to a new lifecycle status")
public class TransitionTimePeriodRequest {

    @NotNull
    @Schema(
            description = "Target lifecycle status",
            example = "SUBMISSION_CLOSED",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private TimePeriodStatus status;
}
