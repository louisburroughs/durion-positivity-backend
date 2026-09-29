package com.positivity.accounting.internal.bankfeed.file.parser;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Reads one statement-file format (SPEC-manual-bank-reconciliation §2.1, §4.3; story S3, #2302).
 * CSV is the phase-1 implementation; OFX, QFX and CAMT.053 are later implementations of this
 * interface, and the core is unchanged by them — the format code never leaves the adapter.
 */
public interface StatementFileParser {

    /** The {@code formatCode} this parser reads, for example {@code CSV}. */
    @NonNull
    String formatCode();

    /** The {@code connectorCode} provenance label the commit puts on the batch, for example {@code csv-v1}. */
    @NonNull
    String connectorCode();

    /**
     * Reads the file. A row that cannot be read is returned rejected with a code; the whole file is
     * refused only when it cannot be read at all (G8).
     *
     * @param content the raw bytes as uploaded
     * @param options the import's parser options
     * @param mapping the column mapping to read with; null for the format's defaults
     * @throws com.positivity.accounting.internal.bankrec.intake.BankRecException {@code
     *     STATEMENT_IMPORT_FAILED} when the file is unreadable: not text in its encoding, binary, or
     *     without a single data row
     */
    @NonNull
    ParsedFile parse(byte @NonNull [] content, @NonNull ParserOptions options, @Nullable ColumnMapping mapping);
}
