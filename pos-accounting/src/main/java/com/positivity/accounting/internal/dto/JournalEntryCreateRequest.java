package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for creating a new Journal Entry.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Journal Entry Request</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for creating or updating a journal entry")
public class JournalEntryCreateRequest {

    @Schema(description = "Organization UUID", example = "00000000-0000-4000-a000-000000000010")
    private UUID organizationId;

    @NotNull(message = "transactionDate is required")
    @Schema(
            description = "Journal transaction date and time",
            example = "2026-01-15T10:30:00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime transactionDate;

    @Size(max = 500, message = "description must not exceed 500 characters")
    @Schema(description = "Journal entry description", example = "Invoice accrual posting")
    private String description;

    @Schema(description = "Source event UUID", example = "01936e5c-1234-7a3d-8b6e-123456789012")
    private UUID sourceEventId;

    @Size(max = 100, message = "sourceEventType must not exceed 100 characters")
    @Schema(description = "Source event type", example = "INVOICE_RECEIVED")
    private String sourceEventType;

    @Schema(description = "Posting rule set UUID used to generate this journal entry")
    private UUID postingRuleSetId;

    @Schema(description = "Posting rule version UUID used to generate this journal entry")
    private UUID postingRuleVersionId;

    @NotEmpty(message = "lines must contain at least one entry")
    @Valid
    @Schema(description = "Journal lines (must net to a balanced entry)", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<JournalEntryLineRequest> lines;

    /**
     * Nested DTO for journal entry lines.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Journal entry line request")
    public static class JournalEntryLineRequest {
        @NotNull(message = "glAccountId is required")
        @Schema(
                description = "GL account UUID",
                example = "01936e5d-1234-7a3d-8b6e-3c4567890123",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private UUID glAccountId;

        @DecimalMin(value = "0.00", inclusive = true, message = "debitAmount must be non-negative")
        @Schema(description = "Debit amount for this line", example = "1250.00")
        private BigDecimal debitAmount;

        @DecimalMin(value = "0.00", inclusive = true, message = "creditAmount must be non-negative")
        @Schema(description = "Credit amount for this line", example = "1250.00")
        private BigDecimal creditAmount;

        @Size(max = 500, message = "description must not exceed 500 characters")
        @Schema(description = "Line description", example = "Recognize receivable")
        private String description;

        @Schema(description = "Optional dimensional attributes for reporting", example = "{\"department\":\"SERVICE\"}")
        private Map<String, String> dimensions;

        @AssertTrue(message = "Exactly one of debitAmount or creditAmount must be greater than zero")
        public boolean isSingleSidedAmount() {
            BigDecimal debit = debitAmount == null ? BigDecimal.ZERO : debitAmount;
            BigDecimal credit = creditAmount == null ? BigDecimal.ZERO : creditAmount;
            boolean hasDebit = debit.signum() > 0;
            boolean hasCredit = credit.signum() > 0;
            return hasDebit ^ hasCredit;
        }
    }
}
