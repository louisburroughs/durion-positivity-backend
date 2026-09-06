package com.positivity.bulkingest;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Schema(description = "Per-record outcome of a bulk ingest submission")
@Data
@Builder
public class BulkIngestResult {

    @Schema(
            description = "Zero-based index of the record within the submitted batch",
            example = "0",
            requiredMode = REQUIRED)
    private int rowIndex;

    @Schema(
            description = "Identifier of the entity created/updated for this record, when successful",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID entityId;

    @Schema(description = "Whether this record was ingested successfully", example = "true", requiredMode = REQUIRED)
    private boolean success;

    @Schema(
            description = "Machine-readable error code when the record failed",
            example = "VALIDATION_FAILED",
            requiredMode = NOT_REQUIRED)
    private String errorCode;

    @Schema(
            description = "Human-readable error detail when the record failed",
            example = "vin must be 17 characters",
            requiredMode = NOT_REQUIRED)
    private String errorMessage;

    @Schema(
            description = "Correlation id to quote when reporting a row that failed for a server-side reason."
                    + " Present only alongside errorCode INTERNAL_ERROR, where it is the whole of what the caller"
                    + " can act on: the exception itself is logged at ERROR against this id and never returned"
                    + " (ADR-0056). Every failed row of one request carries the same id.",
            example = "01960003-0000-7000-8000-0000000000ff",
            requiredMode = NOT_REQUIRED)
    private String correlationId;
}
