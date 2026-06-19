package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Traceability response for a journal entry and related records.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Traceability response linking a journal entry to its source and related entries")
public class JournalEntryTraceabilityResponse {

    @Schema(
            description = "Journal entry UUID",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID journalEntryId;

    @Schema(
            description = "Source event UUID that triggered the journal entry",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID sourceEventId;

    @Schema(description = "The journal entry itself", requiredMode = NOT_REQUIRED)
    private JournalEntryResponse journalEntry;

    @Schema(description = "Original journal entry when this entry is a reversal", requiredMode = NOT_REQUIRED)
    private JournalEntryResponse originalJournalEntry;

    @Schema(description = "Reversal journal entry when this entry has been reversed", requiredMode = NOT_REQUIRED)
    private JournalEntryResponse reversalJournalEntry;

    @Schema(description = "Other journal entries related to the same source event", requiredMode = NOT_REQUIRED)
    private List<JournalEntryResponse> relatedJournalEntries;
}
