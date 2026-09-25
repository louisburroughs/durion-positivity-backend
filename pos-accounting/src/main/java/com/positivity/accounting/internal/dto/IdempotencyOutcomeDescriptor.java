package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.IdempotencyOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One {@link IdempotencyOutcome} constant and its meaning, published under {@link
 * FactConsumptionIdempotency#getOutcomes()} (issue #2207). Built from {@code
 * IdempotencyOutcome.values()} and {@link IdempotencyOutcome#description()} — never a hand-typed
 * list — so a new outcome constant cannot be forgotten in the published contract.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One Kafka fact-consumption idempotency outcome and its meaning")
public class IdempotencyOutcomeDescriptor {

    @Schema(description = "The outcome constant", example = "NEW", requiredMode = REQUIRED)
    private IdempotencyOutcome outcome;

    @Schema(
            description = "Human-readable meaning of the outcome",
            example = "First delivery of this fact; a new AccountingEvent row was written.",
            requiredMode = REQUIRED)
    private String description;
}
