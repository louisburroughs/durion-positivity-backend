package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request to reject a time period approval")
public class RejectTimePeriodRequest {

    @NotBlank(message = "reason is required")
    @Schema(
            description = "Reason the time period is being rejected",
            example = "Missing break entries for two shifts",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;
}
