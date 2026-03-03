package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request payload for applying an emergency override")
public class EmergencyOverrideDTO {
    @NotBlank(message = "exceptionReason is required")
    @Schema(description = "Reason justifying emergency override", example = "Safety-critical repair authorized")
    private String exceptionReason;
}
