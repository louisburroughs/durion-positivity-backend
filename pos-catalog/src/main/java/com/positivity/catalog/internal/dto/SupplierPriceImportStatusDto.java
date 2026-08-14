package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * How much of one PRICAT import this module has applied (ADR-0053 §7).
 *
 * <p>Exists because "did we get the whole catalog" is unanswerable from the price rows alone — a
 * missing chunk and a smaller vendor catalog look identical there. An {@code INCOMPLETE} row is a
 * feed that did not self-heal on its own and is worth an operator's attention, even though a
 * re-emit was already requested for it.
 */
@Schema(description = "Application state of one supplier PRICAT import in this module.")
public record SupplierPriceImportStatusDto(
        @Schema(description = "The producer's import manifest id.", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        @NonNull
        UUID importManifestId,

        @Schema(description = "Vendor profile the import belongs to.", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c")
        @NonNull
        UUID vendorProfileId,

        @Schema(description = "Vendor profile alias at import time.", example = "michelin-eu") @Nullable
        String supplierRef,

        @Schema(
                description = "Application state.",
                example = "COMPLETE",
                allowableValues = {"APPLYING", "COMPLETE", "INCOMPLETE"})
        @NonNull
        String status,

        @Schema(description = "Chunk events applied so far.", example = "25")
        int chunksApplied,

        @Schema(description = "Chunk total the producer declared; null until completion.", example = "25") @Nullable
        Integer expectedChunkCount,

        @Schema(description = "Price rows written from this import.", example = "12310")
        int linesApplied,

        @Schema(description = "Matched-line total the producer reported; null until completion.", example = "12310")
        @Nullable
        Integer expectedLineCount,

        @Schema(description = "When the completion event was applied; null while chunks are in flight.") @Nullable
        Instant completedAt,

        @Schema(description = "When this row last changed.") @NonNull
        Instant updatedAt) {}
