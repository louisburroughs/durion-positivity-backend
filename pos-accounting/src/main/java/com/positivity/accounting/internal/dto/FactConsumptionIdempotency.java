package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The idempotency mechanism used by Kafka fact consumption ({@code
 * InventoryFactIngestionRecorder}, issue #2207): every consumed fact writes a terminal row, and
 * a re-delivery is recognized by its deterministic {@code sourceEventId} rather than being
 * rejected.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Idempotency mechanism for Kafka-consumed inventory posting facts")
public class FactConsumptionIdempotency {

    @Schema(
            description = "Dedup mechanism: a re-delivery is matched by its deterministic sourceEventId",
            example = "DETERMINISTIC_SOURCE_EVENT_ID",
            requiredMode = REQUIRED)
    private String mechanism;

    @Schema(
            description = "Every outcome this path can record, derived from IdempotencyOutcome.values() "
                    + "so it cannot drift from the code",
            requiredMode = REQUIRED)
    private List<IdempotencyOutcomeDescriptor> outcomes;
}
