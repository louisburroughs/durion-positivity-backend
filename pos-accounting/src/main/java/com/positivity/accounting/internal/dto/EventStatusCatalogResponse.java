package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.IdempotencyOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Arrays;
import java.util.List;

/**
 * Catalog of accounting event statuses and idempotency outcomes (issue #2437), generated from
 * {@link AccountingEventStatus#values()} and {@link IdempotencyOutcome#values()} so a new constant
 * cannot be missed.
 */
@Schema(description = "Every accounting event status and idempotency outcome, for rendering list filters")
public record EventStatusCatalogResponse(
        @Schema(description = "One entry per AccountingEventStatus constant", requiredMode = REQUIRED)
        List<StatusEntry> statuses,

        @Schema(description = "One entry per IdempotencyOutcome constant", requiredMode = REQUIRED)
        List<IdempotencyOutcomeEntry> idempotencyOutcomes) {

    /** Builds the catalog from the enums. */
    public static EventStatusCatalogResponse fromEnums() {
        return new EventStatusCatalogResponse(
                Arrays.stream(AccountingEventStatus.values())
                        .map(s -> new StatusEntry(s, s.displayName(), s.meaning(), s.terminal(), s.actionable()))
                        .toList(),
                Arrays.stream(IdempotencyOutcome.values())
                        .map(o -> new IdempotencyOutcomeEntry(o, o.displayName(), o.description()))
                        .toList());
    }

    /** One accounting event status. */
    @Schema(description = "One accounting event status")
    public record StatusEntry(
            @Schema(
                    description = "Enum name, as accepted by the event list status filter",
                    example = "FAILED",
                    requiredMode = REQUIRED)
            AccountingEventStatus code,

            @Schema(description = "Human-readable label", example = "Failed", requiredMode = REQUIRED)
            String displayName,

            @Schema(description = "Meaning of the status", requiredMode = REQUIRED)
            String description,

            @Schema(description = "True when the event can no longer change state", requiredMode = REQUIRED)
            boolean terminal,

            @Schema(description = "True when retry/reprocess applies (FAILED, SUSPENDED)", requiredMode = REQUIRED)
            boolean actionable) {}

    /** One idempotency outcome. */
    @Schema(
            description =
                    "One idempotency outcome, recorded on an accounting event whether it was consumed from Kafka or submitted through the API (e.g. INVOICE_PAYMENT)")
    public record IdempotencyOutcomeEntry(
            @Schema(
                    description = "Enum name, as accepted by the event list idempotency filter",
                    example = "DUPLICATE_IGNORED",
                    requiredMode = REQUIRED)
            IdempotencyOutcome code,

            @Schema(description = "Human-readable label", example = "Duplicate ignored", requiredMode = REQUIRED)
            String displayName,

            @Schema(description = "Meaning of the outcome", requiredMode = REQUIRED)
            String description) {}
}
