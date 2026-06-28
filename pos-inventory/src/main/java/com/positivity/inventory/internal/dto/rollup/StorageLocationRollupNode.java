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
@Schema(description = "One storage location node in the site rollup tree, with own and rolled-up quantities")
public record StorageLocationRollupNode(
        @Schema(
                description = "Storage location identifier",
                example = "01960003-0000-7000-8000-000000000013",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID storageLocationId,

        @Schema(description = "Storage location name", example = "Aisle 3 — Bin 12")
        String name,

        @Schema(description = "Storage location type (e.g. FLOOR, SHELF, BIN)", example = "BIN")
        String type,

        @Schema(description = "Storage location status", example = "ACTIVE")
        String status,

        @Schema(
                description = "Quantities recorded directly at this location",
                requiredMode = Schema.RequiredMode.REQUIRED)
        RollupQuantities own,

        @Schema(description = "Own quantities plus all descendants", requiredMode = Schema.RequiredMode.REQUIRED)
        RollupQuantities rolledUp,

        @Schema(description = "Child storage locations") List<StorageLocationRollupNode> children) {}
