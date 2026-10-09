package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The roles that hold one requested permission code (#2669).
 *
 * @param permission the requested code, in the catalog's spelling
 * @param roles      the holding roles, sorted by name ignoring case; empty when no role holds it
 */
@Schema(description = "The roles of the caller's tenant that hold one requested permission code")
public record PermissionHolders(
        @Schema(
                description = "The requested permission code, matched case-insensitively and answered in the"
                        + " catalog's spelling (for example people:timeEntry:approve)",
                example = "accounting:ap:approve",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String permission,

        @Schema(
                description = "The roles that hold the code, sorted by name ignoring case. Empty when no role holds"
                        + " it, which is a real separation-of-duties answer, not an error.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<PermissionHolderRole> roles) {}
