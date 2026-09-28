package com.positivity.accounting.internal.bankrec.intake;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What {@link BankTransactionIntake#accept} did (SPEC §2.1; story S2, #2301).
 *
 * @param statementId the committed statement, when the batch carried a header
 * @param bankTransactionIds every row the batch created or updated, in batch order
 * @param bankTransactionCount rows created or updated (a {@code REMOVED} notice is not counted)
 * @param possibleDuplicateCount rows that entered as {@code POSSIBLE_DUPLICATE} (R1)
 * @param modifiedCount rows updated through their source id (U3)
 * @param reconciliationBaselineDate the account's baseline after the commit; null when it has none
 * @param baselineChanged whether this commit moved the baseline
 */
public record IntakeResult(
        @Nullable UUID statementId,
        @NonNull List<UUID> bankTransactionIds,
        int bankTransactionCount,
        int possibleDuplicateCount,
        int modifiedCount,
        @Nullable LocalDate reconciliationBaselineDate,
        boolean baselineChanged) {

    public IntakeResult {
        bankTransactionIds = List.copyOf(bankTransactionIds);
    }
}
