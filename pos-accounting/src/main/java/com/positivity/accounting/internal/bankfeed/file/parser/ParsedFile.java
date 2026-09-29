package com.positivity.accounting.internal.bankfeed.file.parser;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A statement file read under a column mapping (SPEC §4.3, §4.4; story S3, #2302).
 *
 * @param columns the column labels in file order: the header row's text, or {@code column 1} …
 *     {@code column n} when the file has no header row
 * @param headerRow whether the first record was recognized as a header row
 * @param mapping the mapping the rows were read with (the request's, the account's or the defaults)
 * @param mappingResolved whether every required column of {@link #mapping} was found; when false the
 *     rows carry only their raw values and the import waits for a mapping ({@code mappingRequired})
 * @param unresolvedColumns the mapping keys that could not be found; empty when resolved
 * @param rows the data rows in file order
 */
public record ParsedFile(
        @NonNull List<String> columns,
        boolean headerRow,
        @NonNull ColumnMapping mapping,
        boolean mappingResolved,
        @NonNull List<String> unresolvedColumns,
        @NonNull List<ParsedRow> rows) {

    public ParsedFile {
        columns = List.copyOf(columns);
        unresolvedColumns = List.copyOf(unresolvedColumns);
        rows = List.copyOf(rows);
    }

    /** A short statement of what is missing, for the rows of an unresolved file. */
    public @Nullable String unresolvedDetail() {
        return mappingResolved ? null : "column mapping required: " + String.join(", ", unresolvedColumns);
    }
}
