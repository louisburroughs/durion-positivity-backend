package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * Entry-number gap-check footnote entry for one monthly sequence scope.
 *
 * Reports sequence numbers that were handed out by the accounting sequence
 * for the scope but have no matching {@code journal_entry.entry_number} row
 * (see {@code AccountingSequenceRepository#findMissingEntryNumbers}).
 * A clean ledger produces no entries at all.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Entry-number gap-check result for one monthly sequence scope; absent gaps mean a clean ledger")
public class EntryNumberGapCheck {

    /**
     * Monthly sequence scope key, e.g. {@code JE-202607}.
     */
    @Schema(description = "Monthly sequence scope key", example = "JE-202607", requiredMode = REQUIRED)
    @NonNull
    private String scopeKey;

    /**
     * Ascending list of sequence numbers missing from the ledger for this scope.
     */
    @Schema(
            description = "Ascending sequence numbers handed out but missing from the ledger for this scope",
            example = "[7, 12]",
            requiredMode = REQUIRED)
    @NonNull
    private List<Long> missingNumbers;
}
