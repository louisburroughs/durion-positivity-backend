package com.positivity.inventory.internal.dto.rollup;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Site inventory rollup: quantities grouped and subtotaled along the
 * storage-location hierarchy (CAP-218 #658).
 *
 * <p>Ledger rows recorded against location ids that pos-location does not
 * list under this site (orphans) are excluded from this response in v1; an
 * {@code unassigned} bucket is deferred to v2.
 */
@Schema(description = "Site inventory rollup with quantities subtotaled along the storage-location hierarchy")
public record SiteInventoryRollupResponse(
        @Schema(
                description = "Site (Location) identifier",
                example = "01960003-0000-7000-8000-000000000011",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID siteId,

        @Schema(
                description = "Site-wide totals (sum of root nodes' rolledUp quantities)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        RollupQuantities totals,

        @Schema(
                description = "Root storage locations of the site topology",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<StorageLocationRollupNode> nodes) {}
