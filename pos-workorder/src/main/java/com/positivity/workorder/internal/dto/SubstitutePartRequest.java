package com.positivity.workorder.internal.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Request DTO for substituting a part with another part.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubstitutePartRequest {

    /**
     * ID of the original part to be substituted.
     */
    @NotNull(message = "Original part ID is required")
    private UUID originalPartId;

    /**
     * ID of the substitute part (new part).
     */
    @NotNull(message = "Substitute part ID is required")
    private UUID substitutePartId;

    /**
     * Reason for substitution (required for audit).
     */
    @NotNull(message = "Reason is required")
    private String reason;

    /**
     * Optional additional notes.
     */
    @Nullable
    private String notes;
}
