package com.positivity.bulkingest;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Schema(description = "Generic bulk ingest request: a batch of records for a job scoped to a location")
@Data
public class BulkIngestRequest<T> {

    @Schema(
            description = "Identifier of the bulk ingest job",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID jobId;

    @Schema(
            description = "Location the records are ingested for",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(
            description = "The records to ingest (at least one); shape depends on the target domain",
            requiredMode = REQUIRED)
    @Valid
    @NotEmpty
    private List<T> records;

    @Schema(
            description = "Identifier of the operator submitting the batch",
            example = "user-jdoe",
            requiredMode = NOT_REQUIRED)
    private String operatorId;
}
