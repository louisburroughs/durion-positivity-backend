package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Describes the expected structure of accounting event envelopes")
public class EventEnvelopeContract {

    @Schema(description = "Contract schema version", example = "1.0", requiredMode = REQUIRED)
    private String version;

    @Schema(description = "Field definitions in the event envelope", requiredMode = REQUIRED)
    private List<ContractField> fields;

    @Schema(description = "Example event payloads", requiredMode = NOT_REQUIRED)
    private List<Object> examples;

    // ========== Additive sections (issue #2207) ==========
    // Each is optional so an older client parsing only version/fields/examples is unaffected.
    // All are sourced from the real ingestion rules at request time, never hand-typed lists that
    // can drift from the code: identifierStrategy documents fixed conventions (AD-006 / ADR-0013),
    // processingStatuses and idempotencyOutcomes.factConsumption.outcomes are derived from
    // AccountingEventStatus.values() and IdempotencyOutcome.values() respectively.

    @Schema(description = "How accounting assigns and accepts envelope identifiers", requiredMode = NOT_REQUIRED)
    private IdentifierStrategy identifierStrategy;

    @Schema(
            description = "Identifiers usable to trace an event across the ingestion pipeline",
            requiredMode = NOT_REQUIRED)
    private List<TraceabilityIdDescriptor> traceabilityIds;

    @Schema(
            description = "Every processing status the pipeline can report, and the lifecycles that " + "produce them",
            requiredMode = NOT_REQUIRED)
    private ProcessingStatusesContract processingStatuses;

    @Schema(
            description = "The two idempotency mechanisms used by the ingestion pipeline: REST "
                    + "submission and Kafka fact consumption",
            requiredMode = NOT_REQUIRED)
    private IdempotencyOutcomesContract idempotencyOutcomes;
}
