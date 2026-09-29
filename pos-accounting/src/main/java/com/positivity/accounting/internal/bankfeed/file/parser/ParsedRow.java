package com.positivity.accounting.internal.bankfeed.file.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One data row of a statement file (SPEC §3.3 {@code BankImportRow}; story S3, #2302): its raw cells
 * and, unless it was rejected, its values normalized to the core convention (positive = cash in).
 *
 * @param rowNumber 1-based position among the file's data rows (the header row is not counted); it
 *     becomes the bank transaction's {@code sourceRowNumber}
 * @param lineNumber 1-based line of the file on which the row starts, for messages
 * @param rawValues column label → cell text as read, never rewritten
 * @param rejection why the row was rejected; null when it parsed
 * @param rejectionDetail a message naming the line and the offending value; null when it parsed
 */
public record ParsedRow(
        int rowNumber,
        int lineNumber,
        @NonNull Map<String, Object> rawValues,
        @Nullable LocalDate date,
        @Nullable BigDecimal signedAmount,
        @Nullable String description,
        @Nullable String reference,
        @Nullable String checkNumber,
        @Nullable String sourceTransactionId,
        @Nullable RejectionCode rejection,
        @Nullable String rejectionDetail) {

    public ParsedRow {
        rawValues = Map.copyOf(rawValues);
    }

    public boolean rejected() {
        return rejection != null;
    }
}
