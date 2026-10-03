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
 * rejects a replay) and Kafka fact consumption (a redelivered envelope is short-circuited by
 * {@code processed_events} and writes no row; any other consumed fact writes one row, terminal
 * except a SUSPENDED currency hold, and a re-emitted fact matched on its listener's posting key
 * posts nothing new).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The two idempotency mechanisms used by the accounting event ingestion pipeline")
public class IdempotencyOutcomesContract {

    @Schema(description = "Idempotency for POST /v1/accounting/events", requiredMode = REQUIRED)
    private RestSubmissionIdempotency restSubmission;

    @Schema(description = "Idempotency for Kafka-consumed posting facts", requiredMode = REQUIRED)
    private FactConsumptionIdempotency factConsumption;
}
