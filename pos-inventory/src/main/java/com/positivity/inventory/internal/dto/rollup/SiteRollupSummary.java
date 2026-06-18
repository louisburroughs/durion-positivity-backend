package com.positivity.inventory.internal.dto.rollup;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Per-site entry of a parent-location rollup (CAP-218 #659). {@code nodes}
 * is present only when {@code expand=tree} was requested.
 */
@Schema(description = "Per-site entry of a parent-location rollup, optionally including the storage-location tree")
public record SiteRollupSummary(
        @Schema(
                description = "Site (Location) identifier",
                example = "01960003-0000-7000-8000-000000000012",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID siteId,

        @Schema(description = "Site name from the location hierarchy", example = "Downtown Service Center")
        String siteName,

        @Schema(
                description = "Site totals (sum of the site's root storage locations)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        RollupQuantities totals,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "Full storage-location rollup tree; present only with expand=tree")
        List<StorageLocationRollupNode> nodes) {}
