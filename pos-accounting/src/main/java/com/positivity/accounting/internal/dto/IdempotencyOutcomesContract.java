package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Additive section of {@link EventEnvelopeContract} (issue #2207) publishing the two distinct
 * idempotency mechanisms the ingestion pipeline uses: REST submission (content-hash dedup,
 * rejects a replay) and Kafka fact consumption (every consumed fact writes a terminal row, a
 * re-delivery reuses the earlier posting).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The two idempotency mechanisms used by the accounting event ingestion pipeline")
public class IdempotencyOutcomesContract {

    @Schema(description = "Idempotency for POST /v1/accounting/events", requiredMode = REQUIRED)
    private RestSubmissionIdempotency restSubmission;

    @Schema(description = "Idempotency for Kafka-consumed inventory posting facts", requiredMode = REQUIRED)
    private FactConsumptionIdempotency factConsumption;
}
