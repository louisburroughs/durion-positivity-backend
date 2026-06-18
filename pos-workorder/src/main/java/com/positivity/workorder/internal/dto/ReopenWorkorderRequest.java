package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for controlled workorder reopen")
public class ReopenWorkorderRequest {

    @Schema(
            description = "Deprecated. Actor identity is resolved from authenticated security context.",
            example = "550e8400-e29b-41d4-a716-446655440000",
            deprecated = true,
            requiredMode = NOT_REQUIRED)
    private UUID userId;

    @NotBlank
    @Schema(
            description = "Mandatory reason for reopening completed workorder",
            example = "Corrected labor hours",
            requiredMode = REQUIRED)
    private String reopenReason;
}
