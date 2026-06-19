package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.positivity.accounting.internal.enums.AccountingEventStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Filter criteria for querying accounting events")
public class AccountingEventFilter {

    @Schema(
            description = "Restrict to events for this organization",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID organizationId;

    @Schema(description = "Restrict to this event type", example = "INVOICE_RECEIVED", requiredMode = NOT_REQUIRED)
    @Nullable
    private String eventType;

    @Schema(description = "Restrict to events in this processing status", example = "PROCESSED", requiredMode = NOT_REQUIRED)
    @Nullable
    private AccountingEventStatus status;

    @Schema(description = "Restrict to events with this idempotency outcome", example = "NEW", requiredMode = NOT_REQUIRED)
    @Nullable
    private String idempotencyOutcome;

    @Schema(
            description = "Lower bound (inclusive) for received timestamp (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private Instant receivedAtFrom;

    @Schema(
            description = "Upper bound (inclusive) for received timestamp (ISO 8601)",
            example = "2026-06-18T18:00:00Z",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private Instant receivedAtTo;

    @Schema(
            description = "Restrict to a specific event identifier",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID eventId;

    @Schema(
            description = "Restrict to a specific ingestion identifier",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID ingestionId;

    @Schema(description = "Restrict to events for this domain key", example = "INVOICE-2026-000123", requiredMode = NOT_REQUIRED)
    @Nullable
    private String domainKeyId;

    @Schema(
            description = "Restrict to events for this invoice",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID invoiceId;
}
