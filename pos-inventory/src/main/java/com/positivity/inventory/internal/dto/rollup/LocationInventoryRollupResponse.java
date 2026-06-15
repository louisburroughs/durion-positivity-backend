package com.positivity.inventory.internal.dto.rollup;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Parent-location inventory rollup: site totals aggregated across all
 * descendant sites of a location along a typed parent chain (CAP-218 #659).
 */
public record LocationInventoryRollupResponse(
        @Schema(
                description = "Parent location identifier (building/place/region)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID locationId,

        @Schema(
                description = "Parent chain type used for descendant resolution",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String parentType,

        @Schema(description = "Grand total across all descendant sites", requiredMode = Schema.RequiredMode.REQUIRED)
        RollupQuantities totals,

        @Schema(
                description = "Per-site summaries (trees inlined with expand=tree)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<SiteRollupSummary> sites) {}
