package com.positivity.inventory.internal.dto.rollup;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * One storage location in the site rollup tree.
 *
 * <p>{@code own} is the quantity recorded directly against this storage
 * location; {@code rolledUp} is {@code own} plus all descendants' {@code own}.
 */
public record StorageLocationRollupNode(
        @Schema(description = "Storage location identifier", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID storageLocationId,

        @Schema(description = "Storage location name") String name,

        @Schema(description = "Storage location type (e.g. FLOOR, SHELF, BIN)")
        String type,

        @Schema(description = "Storage location status") String status,

        @Schema(
                description = "Quantities recorded directly at this location",
                requiredMode = Schema.RequiredMode.REQUIRED)
        RollupQuantities own,

        @Schema(description = "Own quantities plus all descendants", requiredMode = Schema.RequiredMode.REQUIRED)
        RollupQuantities rolledUp,

        @Schema(description = "Child storage locations") List<StorageLocationRollupNode> children) {}
