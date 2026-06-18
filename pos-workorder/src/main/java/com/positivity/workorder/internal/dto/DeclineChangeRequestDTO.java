package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload for declining a change request")
public class DeclineChangeRequestDTO {
    @NotBlank(message = "approvalNote is required")
    @Schema(
            description = "Decline note captured as the decision artifact",
            example = "Declined by customer",
            requiredMode = REQUIRED)
    private String approvalNote;
}
