package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Additive section of {@link EventEnvelopeContract} (issue #2207) publishing every processing
 * status the pipeline can report, and the two distinct lifecycles a status sequence can follow:
 * a REST-submitted event moves through non-terminal states before landing on a terminal one,
 * while a Kafka-consumed posting fact writes a single row directly: terminal (PROCESSED or SKIPPED),
 * except a hold: a currency hold (SUSPENDED / CURRENCY_NOT_SUPPORTED, ADR-0067 PC-9, #2334), a malformed
 * goods receipt (SUSPENDED / VALIDATION_ERROR, #2602) or a settled payment its automatic application
 * could not complete (SUSPENDED or FAILED, #2503). A manual reprocess routes a held goods receipt, register
 * session, invoice or settled payment back to its own path; a held Kafka row with no such path (a settled payment
 * held for its currency) is refused with 409 ACCOUNTING_EVENT_HOLD_NOT_RELEASABLE and keeps its reason (#2620): a
 * currency hold is released only when the ledger accepts the currency (ADR-0067 Stage B1).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Every accounting event processing status, and the lifecycles that produce them")
public class ProcessingStatusesContract {

    @Schema(
            description = "Every AccountingEventStatus constant with its meaning, derived from the enum "
                    + "so it cannot drift from the code",
            requiredMode = REQUIRED)
    private List<ProcessingStatusDescriptor> statuses;

    @Schema(
            description = "Status sequence for an event submitted via POST /v1/accounting/events",
            example = "[\"RECEIVED\", \"PROCESSING\", \"PROCESSED|FAILED|SUSPENDED\"]",
            requiredMode = REQUIRED)
    private List<String> restSubmissionLifecycle;

    @Schema(
            description = "Status sequence for a Kafka-consumed posting fact: exactly one row is written "
                    + "per consumed fact, never RECEIVED or PROCESSING. It is terminal (PROCESSED or SKIPPED), "
                    + "except a hold. A fact held for its currency is SUSPENDED with failureReasonCode "
                    + "CURRENCY_NOT_SUPPORTED, and a malformed goods receipt is SUSPENDED with VALIDATION_ERROR; "
                    + "neither is auto-retried. A settled payment its automatic application could not complete is "
                    + "SUSPENDED or FAILED with its own reason. A manual reprocess re-runs a held goods receipt's, "
                    + "register session's, invoice's or settled payment's own path from the stored fact, never the "
                    + "posting rules; a held Kafka row with no such path (a settled payment held for its currency) "
                    + "is refused with 409 ACCOUNTING_EVENT_HOLD_NOT_RELEASABLE and keeps its reason: a currency "
                    + "hold is released only when the ledger accepts the currency (ADR-0067 Stage B1), never by "
                    + "reprocess (#2620)",
            example = "[\"PROCESSED|SKIPPED|SUSPENDED\"]",
            requiredMode = REQUIRED)
    private List<String> kafkaFactLifecycle;
}
