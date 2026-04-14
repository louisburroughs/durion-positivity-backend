package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
    @Schema(description = "Original part identifier", example = "550e8400-e29b-41d4-a716-446655440500")
    private UUID originalPartId;

    /**
     * ID of the substitute part (new part).
     */
    @NotNull(message = "Substitute part ID is required")
    @Schema(description = "Substitute part identifier", example = "550e8400-e29b-41d4-a716-446655440501")
    private UUID substitutePartId;

    /**
     * Reason for substitution (required for audit).
     */
    @NotNull(message = "Reason is required")
    @Schema(
            description = "Audit reason for substitution",
            example = "Original part unavailable, equivalent substitute used")
    private String reason;

    /**
     * Optional additional notes.
     */
    @Nullable
    @Schema(description = "Optional substitution notes", example = "Customer notified and approved substitute")
    private String notes;
}
