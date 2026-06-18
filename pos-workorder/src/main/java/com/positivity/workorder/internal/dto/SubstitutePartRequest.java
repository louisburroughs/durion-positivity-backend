package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Request DTO for substituting a part with another part.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload for substituting an authorized part")
public class SubstitutePartRequest {

    /**
     * ID of the original part to be substituted.
     */
    @NotNull(message = "Original part ID is required")
    @Schema(
            description = "Original part identifier",
            example = "550e8400-e29b-41d4-a716-446655440500",
            requiredMode = REQUIRED)
    private UUID originalPartId;

    /**
     * ID of the substitute part (new part).
     */
    @NotNull(message = "Substitute part ID is required")
    @Schema(
            description = "Substitute part identifier",
            example = "550e8400-e29b-41d4-a716-446655440501",
            requiredMode = REQUIRED)
    private UUID substitutePartId;

    /**
     * Reason for substitution (required for audit).
     */
    @NotBlank(message = "Reason is required")
    @Schema(
            description = "Audit reason for substitution",
            example = "Original part unavailable, equivalent substitute used",
            requiredMode = REQUIRED)
    private String reason;

    /**
     * Optional additional notes.
     */
    @Nullable
    @Schema(
            description = "Optional substitution notes",
            example = "Customer notified and approved substitute",
            requiredMode = NOT_REQUIRED)
    private String notes;
}
