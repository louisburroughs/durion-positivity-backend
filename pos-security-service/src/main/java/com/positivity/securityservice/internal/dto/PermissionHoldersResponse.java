package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Which of the caller's tenant's roles hold each requested permission code (#2669).
 *
 * <p>An object wrapper rather than a bare array, so fields can be added later without a breaking
 * change.
 *
 * @param permissions one entry per distinct requested code, in request order
 */
@Schema(description = "Which of the caller's tenant's roles hold each requested permission code")
public record PermissionHoldersResponse(
        @Schema(
                description = "One entry per distinct requested code, in the order first requested. Every requested"
                        + " code appears, with an empty roles list when no role holds it.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<PermissionHolders> permissions) {}
