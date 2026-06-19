package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.AccountingEventStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Tolerate;

/**
 * DTO for accounting event response.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Accounting Event Response</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Accounting event ingestion record and its processing outcome")
public class AccountingEventResponse {

    @Schema(
            description = "Unique identifier of the accounting event",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID eventId;

    @Schema(
            description = "Identifier of the owning organization",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID organizationId;

    @Schema(description = "Event type identifier", example = "INVOICE_RECEIVED", requiredMode = REQUIRED)
    @NotNull
    private String eventType;

    @Schema(description = "Source system that emitted the event", example = "POS_ORDER", requiredMode = NOT_REQUIRED)
    private String sourceSystem;

    @Schema(description = "Business transaction timestamp", example = "2026-06-18T10:30:00", requiredMode = NOT_REQUIRED)
    private LocalDateTime transactionDate;

    @Schema(description = "Event-specific payload content", requiredMode = NOT_REQUIRED)
    private Map<String, Object> payload;

    @Schema(description = "Processing status of the event", example = "PROCESSED", requiredMode = REQUIRED)
    @NotNull
    private AccountingEventStatus status;

    @Schema(
            description = "Identifier of the journal entry produced, if any",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID journalEntryId;

    @Schema(description = "Error message when processing failed", example = "Mapping not found for event type", requiredMode = NOT_REQUIRED)
    private String errorMessage;

    @Schema(
            description = "Timestamp when the event was received (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant receivedAt;

    @Schema(
            description = "Timestamp when the event was processed (ISO 8601)",
            example = "2026-06-18T08:00:05Z",
            requiredMode = NOT_REQUIRED)
    private Instant processedAt;

    @Schema(description = "Monotonic sequence number assigned to the event", example = "42", requiredMode = NOT_REQUIRED)
    private Long sequenceNumber;

    // ========== Suspense Queue Fields (CAP:055) ==========
    @Schema(description = "Code categorizing the failure reason", example = "MAPPING_NOT_FOUND", requiredMode = NOT_REQUIRED)
    private String failureReasonCode;

    @Schema(description = "Detailed failure description", example = "No GL mapping configured for INVOICE_RECEIVED", requiredMode = NOT_REQUIRED)
    private String failureDetails;

    @Schema(description = "Number of processing attempts made", example = "3", requiredMode = NOT_REQUIRED)
    private Integer attemptCount;

    @Schema(description = "Posting reference assigned on final resolution", example = "JE-2026-000456", requiredMode = NOT_REQUIRED)
    private String finalPostingReferenceId;

    @Schema(
            description = "Identifier of the user who resolved the event",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    private String resolvedByUserId;

    @Schema(description = "Mapping version attempted during processing", example = "v3", requiredMode = NOT_REQUIRED)
    private String mappingVersionAttempted;

    @Schema(description = "Idempotency outcome of ingestion", example = "NEW", requiredMode = NOT_REQUIRED)
    private String idempotencyOutcome;

    @Schema(
            description = "Identifier of the ingestion batch",
            example = "01960003-0000-7000-8000-000000000005",
            requiredMode = NOT_REQUIRED)
    private UUID ingestionId;

    @Schema(description = "Domain key associated with the event", example = "INVOICE-2026-000123", requiredMode = NOT_REQUIRED)
    private String domainKeyId;

    @Schema(
            description = "Identifier of the related invoice, if any",
            example = "01960003-0000-7000-8000-000000000006",
            requiredMode = NOT_REQUIRED)
    private UUID invoiceId;

    /**
     * Convenience setter that parses a status string into the enum.
     */
    @Tolerate
    public void setStatus(String status) {
        if (status != null) {
            try {
                this.status = AccountingEventStatus.valueOf(status);
            } catch (IllegalArgumentException _) {
                this.status = null;
            }
        }
    }
}
