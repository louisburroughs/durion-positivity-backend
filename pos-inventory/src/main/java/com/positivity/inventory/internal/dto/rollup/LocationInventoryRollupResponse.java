package com.positivity.inventory.internal.dto.rollup;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Parent-location inventory rollup: site totals aggregated across all
 * descendant sites of a location along a typed parent chain (CAP-218 #659).
 */
@Schema(description = "Parent-location inventory rollup aggregating site totals across all descendant sites")
public record LocationInventoryRollupResponse(
        @Schema(
                description = "Parent location identifier (building/place/region)",
                example = "01960003-0000-7000-8000-000000000010",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID locationId,

        @Schema(
                description = "Parent chain type used for descendant resolution",
                example = "BUILDING",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String parentType,

        @Schema(
                description = "Grand total across all descendant sites",
                requiredMode = Schema.RequiredMode.REQUIRED)
        RollupQuantities totals,

        @Schema(
                description = "Per-site summaries (trees inlined with expand=tree)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<SiteRollupSummary> sites) {}
