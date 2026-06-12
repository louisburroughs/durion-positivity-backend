package com.positivity.inventory.internal.dto.rollup;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Per-site entry of a parent-location rollup (CAP-218 #659). {@code nodes}
 * is present only when {@code expand=tree} was requested.
 */
public record SiteRollupSummary(
        @Schema(description = "Site (Location) identifier", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID siteId,

        @Schema(description = "Site name from the location hierarchy")
        String siteName,

        @Schema(
                description = "Site totals (sum of the site's root storage locations)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        RollupQuantities totals,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "Full storage-location rollup tree; present only with expand=tree")
        List<StorageLocationRollupNode> nodes) {}
