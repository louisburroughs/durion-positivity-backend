package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.IdempotencyOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One consuming listener's posting-deduplication key, published under {@link
 * FactConsumptionIdempotency#getPostingDeduplication()} (issue #2433): how a re-emitted fact — a new
 * envelope {@code eventId} carrying a business fact accounting already consumed — is recognized,
 * and what its {@code accounting_event} row then records.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description =
                "How one consuming listener recognizes a re-emitted fact (new envelope eventId, same business fact)")
public class FactPostingKeyDescriptor {

    @Schema(
            description = "Producing module stamped as the rows' sourceSystem",
            example = "pos-inventory",
            requiredMode = REQUIRED)
    private String sourceSystem;

    @Schema(description = "Fact event types this key covers", requiredMode = REQUIRED)
    private List<String> eventTypes;

    @Schema(
            description = "The business key a re-emitted fact is matched on",
            example =
                    "Deterministic sourceEventId derived from the scrapId / adjustmentKind + adjustmentId / revaluationId",
            requiredMode = REQUIRED)
    private String postingKey;

    @Schema(description = "Whether a fact under this key posts a journal entry", requiredMode = REQUIRED)
    private boolean postsJournalEntry;

    @Schema(
            description = "Outcome recorded for a re-emitted fact matched on the key; absent when this source never "
                    + "records DUPLICATE_IGNORED",
            example = "DUPLICATE_IGNORED",
            requiredMode = NOT_REQUIRED)
    private IdempotencyOutcome duplicateOutcome;

    @Schema(description = "What a re-emitted fact matched on the key does", requiredMode = REQUIRED)
    private String onDuplicate;
}
