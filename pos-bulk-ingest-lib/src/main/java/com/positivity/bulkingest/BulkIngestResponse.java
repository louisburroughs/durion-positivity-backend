package com.positivity.bulkingest;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Schema(description = "Summary outcome of a bulk ingest job: per-record results plus aggregate counts")
@Data
@Builder
public class BulkIngestResponse {

    @Schema(description = "Total number of records submitted in the batch", example = "100", requiredMode = REQUIRED)
    private int totalSubmitted;

    @Schema(description = "Number of records ingested successfully", example = "98", requiredMode = REQUIRED)
    private int successCount;

    @Schema(description = "Number of records that failed ingest", example = "2", requiredMode = REQUIRED)
    private int failureCount;

    @Schema(description = "Per-record outcomes in submission order", requiredMode = REQUIRED)
    private List<BulkIngestResult> results;
}
