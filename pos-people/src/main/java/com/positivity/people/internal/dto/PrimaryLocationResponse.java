package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for the current user's primary location assignment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Primary location resolved from the authenticated user's active staffing assignments")
public class PrimaryLocationResponse {

    @Schema(
            description = "Location identifier of the user's primary active assignment",
            example = "22222222-2222-2222-2222-222222222222",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID locationId;

    @Schema(
            description = "True when the caller has no active primary assignment and locationId carries the"
                    + " platform's top-level default location instead",
            example = "false",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private boolean defaulted;
}
