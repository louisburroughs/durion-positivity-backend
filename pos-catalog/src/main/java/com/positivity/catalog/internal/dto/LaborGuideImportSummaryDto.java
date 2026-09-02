package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(
        description = "Outcome of one labor-guide import run. Completeness is counted, never assumed:"
                + " COMPLETE means every expected chunk applied and the line count reconciled.")
public class LaborGuideImportSummaryDto {

    @Schema(description = "Provider-assigned manifest identity of the imported revision", requiredMode = REQUIRED)
    private UUID importManifestId;

    @Schema(description = "Source the feed came from", example = "MOCKGUIDE", requiredMode = REQUIRED)
    private String sourceCode;

    @Schema(description = "Feed revision the rows carry", example = "2026-09-01", requiredMode = REQUIRED)
    private String sourceRevision;

    @Schema(description = "APPLYING | COMPLETE | INCOMPLETE", requiredMode = REQUIRED)
    private String status;

    @Schema(description = "Chunks applied so far vs expected", requiredMode = REQUIRED)
    private int chunksApplied;

    @Schema(requiredMode = REQUIRED)
    private int expectedChunkCount;

    @Schema(description = "Feed lines seen (mapped + unmapped) vs expected", requiredMode = REQUIRED)
    private long linesApplied;

    @Schema(requiredMode = REQUIRED)
    private long expectedLineCount;

    @Schema(description = "Lines skipped into the unmapped-operation queue", requiredMode = REQUIRED)
    private long linesUnmapped;

    @Schema(description = "Standards inserted (new or superseding replacements) this run", requiredMode = REQUIRED)
    private long standardsWritten;

    @Schema(description = "Lines identical to the active row — no write", requiredMode = REQUIRED)
    private long linesUnchanged;

    @Schema(description = "When the import reached a terminal status", requiredMode = NOT_REQUIRED)
    private Instant completedAt;

    @Schema(
            description = "True when this run found the revision already imported and did nothing",
            requiredMode = REQUIRED)
    private boolean alreadyImported;
}
