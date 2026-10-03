package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The idempotency of Kafka fact consumption ({@code KafkaFactIngestionRecorder}, issues #2207,
 * #2433), in two layers. <b>Envelope deduplication:</b> a redelivered envelope (same {@code
 * eventId}) is short-circuited by {@code processed_events} and writes no {@code accounting_event}
 * row. <b>Posting deduplication:</b> a re-emitted fact (new envelope {@code eventId}, same business
 * fact) writes a row of its own, matched on a posting key that differs per listener ({@link
 * #postingDeduplication}); a matched fact records {@code DUPLICATE_IGNORED} where that source
 * records it. Every consumed fact that passes the first layer writes one row (terminal, except a
 * SUSPENDED currency hold).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Idempotency for Kafka-consumed posting facts: envelope deduplication by eventId, then "
                + "per-listener posting deduplication by business key")
public class FactConsumptionIdempotency {

    @Schema(
            description = "Posting-deduplication mechanism of the journal-entry-posting facts (pos-inventory, "
                    + "pos-invoice, pos-order): a re-emitted fact is matched by its deterministic sourceEventId. "
                    + "Sources that post no journal entry key differently; postingDeduplication lists every key",
            example = "DETERMINISTIC_SOURCE_EVENT_ID",
            requiredMode = REQUIRED)
    private String mechanism;

    @Schema(
            description = "Envelope deduplication: a redelivered envelope (same eventId) is short-circuited by "
                    + "processed_events before any work, and writes NO accounting_event row and no outcome",
            example = "PROCESSED_EVENTS_BY_EVENT_ID",
            requiredMode = REQUIRED)
    private String envelopeDeduplication;

    @Schema(
            description = "Posting deduplication, per listener: the business key a re-emitted fact (new envelope "
                    + "eventId, same business fact) is matched on, and what its row then records",
            requiredMode = REQUIRED)
    private List<FactPostingKeyDescriptor> postingDeduplication;

    @Schema(
            description = "Every outcome this path can record, derived from IdempotencyOutcome.values() "
                    + "so it cannot drift from the code",
            requiredMode = REQUIRED)
    private List<IdempotencyOutcomeDescriptor> outcomes;
}
