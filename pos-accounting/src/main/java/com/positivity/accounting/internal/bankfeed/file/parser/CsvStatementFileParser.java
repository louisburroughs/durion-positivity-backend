package com.positivity.accounting.internal.bankfeed.file.parser;

import com.positivity.accounting.internal.bankfeed.file.parser.ColumnMapping.ColumnRef;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The CSV statement parser (SPEC-manual-bank-reconciliation §4.3, §4.4; story S3, #2302), generalized
 * from the F2 {@code BankStatementCsvParser}: RFC 4180 records (quoted fields with embedded delimiters,
 * line breaks and doubled quotes), raw or base64 text, header-row auto-detection, a column mapping by
 * header name or position, the three sign conventions and the date, decimal, encoding and delimiter
 * options. A bad row is rejected with a {@link RejectionCode}; only an unreadable file is refused.
 */
@Component
public class CsvStatementFileParser implements StatementFileParser {

    public static final String FORMAT_CODE = "CSV";
    public static final String CONNECTOR_CODE = "csv-v1";

    /** Column widths of {@code bank_import_row}; longer values are cut, the raw cell is kept. */
    private static final int DESCRIPTION_LENGTH = 500;

    private static final int REFERENCE_LENGTH = 255;
    private static final int CHECK_NUMBER_LENGTH = 32;
    private static final int SOURCE_ID_LENGTH = 128;
    private static final int MAX_SCALE = 4;

    @Override
    public @NonNull String formatCode() {
        return FORMAT_CODE;
    }

    @Override
    public @NonNull String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public @NonNull ParsedFile parse(
            byte @NonNull [] content, @NonNull ParserOptions options, @Nullable ColumnMapping mapping) {
        String text = decodeIfBase64(decode(content, options), options);
        List<Record> records = readRecords(text, options.delimiter());
        if (records.isEmpty()) {
            throw unreadable("The file contains no rows");
        }

        boolean headerRow = isHeaderRow(records.get(0).cells(), options);
        List<Record> data = headerRow ? records.subList(1, records.size()) : records;
        if (data.isEmpty()) {
            throw unreadable("The file contains a header row but no data rows");
        }
        List<String> columns = columns(headerRow ? records.get(0).cells() : null, records);

        ColumnMapping effective = mapping != null
                ? mapping
                : headerRow ? ColumnMapping.defaultsByName() : ColumnMapping.defaultsByPosition();
        Resolution resolution = resolve(effective, headerRow ? columns : null, columns.size(), options);

        List<ParsedRow> rows = new ArrayList<>(data.size());
        for (int i = 0; i < data.size(); i++) {
            Record record = data.get(i);
            Map<String, Object> raw = rawValues(columns, record.cells());
            if (!resolution.resolved()) {
                rows.add(new ParsedRow(
                        i + 1,
                        record.line(),
                        raw,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        RejectionCode.REQUIRED_COLUMN_MISSING,
                        "Line " + record.line() + ": column mapping required: "
                                + String.join(", ", resolution.missing())));
            } else {
                rows.add(parseRow(i + 1, record, raw, resolution, options));
            }
        }
        return new ParsedFile(columns, headerRow, effective, resolution.resolved(), resolution.missing(), rows);
    }

    // ---- decoding ------------------------------------------------------------------------------

    private static String decode(byte[] content, ParserOptions options) {
        if (content.length == 0) {
            throw unreadable("The file is empty");
        }
        String text;
        try {
            text = options.charset()
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException notText) {
            throw unreadable("The file is not " + options.charset().name() + " text; check the encoding option");
        }
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        if (text.indexOf('\0') >= 0) {
            throw unreadable("The file is binary, not delimited text");
        }
        if (text.isBlank()) {
            throw unreadable("The file contains no rows");
        }
        return text;
    }

    /**
     * Base64 heuristic (F2): delimited text always contains the delimiter, which is not in the base64
     * alphabet; a delimiter-free input that decodes to text containing it is taken as base64.
     */
    private static String decodeIfBase64(String text, ParserOptions options) {
        String trimmed = text.trim();
        if (trimmed.indexOf(options.delimiter()) >= 0) {
            return text;
        }
        try {
            byte[] decoded = Base64.getMimeDecoder().decode(trimmed);
            String decodedText = decode(decoded, options);
            if (decodedText.indexOf(options.delimiter()) >= 0) {
                return decodedText;
            }
        } catch (IllegalArgumentException | BankRecException notBase64) {
            // not base64 text; read it as it is
        }
        return text;
    }

    private static BankRecException unreadable(String message) {
        return new BankRecException(BankRecErrorCode.STATEMENT_IMPORT_FAILED, message);
    }

    // ---- records -------------------------------------------------------------------------------

    /** One record of the file: its cells as read and the 1-based line it starts on. */
    private record Record(int line, List<String> cells) {}

    /**
     * RFC 4180 records: a quote opens a field that may hold the delimiter, line breaks and doubled
     * quotes; blank lines are skipped.
     */
    static List<Record> readRecords(String text, char delimiter) {
        List<Record> records = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        int line = 1;
        int recordLine = 1;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < length && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    cell.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delimiter) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
                    i++;
                }
                cells.add(cell.toString());
                cell.setLength(0);
                addIfNotBlank(records, recordLine, cells);
                cells = new ArrayList<>();
                line++;
                recordLine = line;
            } else {
                cell.append(c);
            }
        }
        if (inQuotes) {
            // Accepting it would fold every later row into one cell of this record.
            throw unreadable("Line " + recordLine + ": a quoted field is not closed before the end of the file");
        }
        cells.add(cell.toString());
        addIfNotBlank(records, recordLine, cells);
        return records;
    }

    private static void addIfNotBlank(List<Record> records, int line, List<String> cells) {
        if (cells.stream().anyMatch(value -> !value.isBlank())) {
            records.add(new Record(line, List.copyOf(cells)));
        }
    }

    /**
     * The first record is a header row when none of its cells reads as a date or an amount: a
     * header-less first data row always carries an amount, so a malformed one is rejected rather than
     * silently taken as a header (F2).
     */
    private static boolean isHeaderRow(List<String> first, ParserOptions options) {
        for (String value : first) {
            if (StatementValues.parseDate(value, options) != null
                    || StatementValues.parseAmount(value, options) != null) {
                return false;
            }
        }
        return true;
    }

    private static List<String> columns(@Nullable List<String> header, List<Record> records) {
        int width = records.stream().mapToInt(r -> r.cells().size()).max().orElse(0);
        List<String> columns = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            String label = header != null && i < header.size() ? header.get(i).trim() : "";
            if (label.isEmpty() || columns.contains(label)) {
                label = "column " + (i + 1);
            }
            columns.add(label);
        }
        return columns;
    }

    private static Map<String, Object> rawValues(List<String> columns, List<String> cells) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < cells.size() && i < columns.size(); i++) {
            raw.put(columns.get(i), cells.get(i));
        }
        return raw;
    }

    // ---- mapping -------------------------------------------------------------------------------

    /** Column positions of a resolved mapping; -1 for an unmapped optional column. */
    private record Resolution(
            boolean resolved,
            List<String> missing,
            int date,
            int description,
            int amount,
            int debit,
            int credit,
            int reference,
            int checkNumber,
            int sourceTransactionId) {}

    private static Resolution resolve(
            ColumnMapping mapping, @Nullable List<String> header, int width, ParserOptions options) {
        List<String> missing = new ArrayList<>();
        int date = position(mapping.date(), header, width, "date", true, missing);
        int description = position(mapping.description(), header, width, "description", true, missing);
        boolean debitCredit = options.signConvention() == SignConvention.DEBIT_CREDIT_COLUMNS;
        int amount = position(mapping.amount(), header, width, "amount", !debitCredit, missing);
        int debit = position(mapping.debit(), header, width, "debit", debitCredit, missing);
        int credit = position(mapping.credit(), header, width, "credit", debitCredit, missing);
        // Optional columns: a default that the file does not have is simply unmapped.
        int reference = position(mapping.reference(), header, width, "reference", false, missing);
        int checkNumber = position(mapping.checkNumber(), header, width, "checkNumber", false, missing);
        int sourceId = position(mapping.sourceTransactionId(), header, width, "sourceTransactionId", false, missing);
        return new Resolution(
                missing.isEmpty(), missing, date, description, amount, debit, credit, reference, checkNumber, sourceId);
    }

    private static int position(
            @Nullable ColumnRef ref,
            @Nullable List<String> header,
            int width,
            String key,
            boolean required,
            List<String> missing) {
        int found = -1;
        if (ref != null && ref.index() != null) {
            found = ref.index() < width ? ref.index() : -1;
        } else if (ref != null && ref.name() != null && header != null) {
            String wanted = ref.name().trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < header.size(); i++) {
                if (header.get(i).trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                    found = i;
                    break;
                }
            }
        }
        if (found < 0 && required) {
            missing.add(key);
        }
        return found;
    }

    // ---- rows ----------------------------------------------------------------------------------

    private static ParsedRow parseRow(
            int rowNumber, Record record, Map<String, Object> raw, Resolution at, ParserOptions options) {
        List<String> cells = record.cells();
        int line = record.line();
        String dateCell = cell(cells, at.date());
        String description = trimmed(cell(cells, at.description()));
        String reference = cut(trimmed(cell(cells, at.reference())), REFERENCE_LENGTH);
        String checkNumber = cut(trimmed(cell(cells, at.checkNumber())), CHECK_NUMBER_LENGTH);
        String sourceId = cut(trimmed(cell(cells, at.sourceTransactionId())), SOURCE_ID_LENGTH);
        description = cut(description, DESCRIPTION_LENGTH);

        // Every value is read even when another fails, so a correction only has to supply the bad one;
        // the first failure in column order names the row's rejection.
        RejectionCode rejection = null;
        String detail = null;
        LocalDate date = null;
        if (dateCell == null || dateCell.isBlank()) {
            rejection = RejectionCode.REQUIRED_COLUMN_MISSING;
            detail = "Line " + line + ": no date";
        } else {
            date = StatementValues.parseDate(dateCell, options);
            if (date == null) {
                rejection = RejectionCode.DATE_UNPARSEABLE;
                detail = "Line " + line + ": unparseable date '" + dateCell.trim() + "'";
            }
        }
        if (description == null && rejection == null) {
            rejection = RejectionCode.REQUIRED_COLUMN_MISSING;
            detail = "Line " + line + ": no description";
        }
        Amount amount = options.signConvention() == SignConvention.DEBIT_CREDIT_COLUMNS
                ? debitCredit(cell(cells, at.debit()), cell(cells, at.credit()), options, line)
                : signed(cell(cells, at.amount()), options, line);
        if (amount.rejection() != null && rejection == null) {
            rejection = amount.rejection();
            detail = amount.detail();
        }
        return new ParsedRow(
                rowNumber,
                line,
                raw,
                date,
                amount.value(),
                description,
                reference,
                checkNumber,
                sourceId,
                rejection,
                detail);
    }

    /** A parsed amount in the core convention, or why it could not be read. */
    private record Amount(
            @Nullable BigDecimal value,
            @Nullable RejectionCode rejection,
            @Nullable String detail) {

        static Amount of(BigDecimal value) {
            return new Amount(value, null, null);
        }

        static Amount rejected(RejectionCode code, String detail) {
            return new Amount(null, code, detail);
        }
    }

    private static Amount signed(@Nullable String cell, ParserOptions options, int line) {
        if (cell == null || cell.isBlank()) {
            return Amount.rejected(RejectionCode.REQUIRED_COLUMN_MISSING, "Line " + line + ": no amount");
        }
        BigDecimal value = StatementValues.parseAmount(cell, options);
        Amount checked = check(value, cell, line);
        if (checked.rejection() != null) {
            return checked;
        }
        return Amount.of(options.signConvention() == SignConvention.SIGNED_AMOUNT_INVERTED ? value.negate() : value);
    }

    private static Amount debitCredit(
            @Nullable String debitCell, @Nullable String creditCell, ParserOptions options, int line) {
        boolean hasDebit = hasAmount(debitCell, options);
        boolean hasCredit = hasAmount(creditCell, options);
        if (hasDebit && hasCredit) {
            return Amount.rejected(
                    RejectionCode.AMOUNT_AND_DEBIT_CREDIT_BOTH,
                    "Line " + line + ": both debit '" + debitCell.trim() + "' and credit '" + creditCell.trim()
                            + "' carry an amount");
        }
        String cell = hasDebit ? debitCell : hasCredit ? creditCell : firstNonBlank(debitCell, creditCell);
        if (cell == null) {
            return Amount.rejected(RejectionCode.REQUIRED_COLUMN_MISSING, "Line " + line + ": no debit or credit");
        }
        BigDecimal value = StatementValues.parseAmount(cell, options);
        Amount checked = check(value, cell, line);
        if (checked.rejection() != null) {
            return checked;
        }
        // Credit = cash in, debit = cash out; the columns carry magnitudes, whatever sign the bank printed.
        return Amount.of(hasDebit ? value.abs().negate() : value.abs());
    }

    private static boolean hasAmount(@Nullable String cell, ParserOptions options) {
        if (cell == null || cell.isBlank()) {
            return false;
        }
        BigDecimal value = StatementValues.parseAmount(cell, options);
        // An unparseable cell counts as present, so it is rejected as unparseable rather than ignored.
        return value == null || value.signum() != 0;
    }

    private static Amount check(@Nullable BigDecimal value, String cell, int line) {
        if (value == null) {
            return Amount.rejected(
                    RejectionCode.AMOUNT_UNPARSEABLE, "Line " + line + ": unparseable amount '" + cell.trim() + "'");
        }
        if (value.signum() == 0) {
            return Amount.rejected(RejectionCode.AMOUNT_ZERO, "Line " + line + ": the amount is zero");
        }
        if (value.stripTrailingZeros().scale() > MAX_SCALE) {
            return Amount.rejected(
                    RejectionCode.AMOUNT_UNPARSEABLE,
                    "Line " + line + ": amount '" + cell.trim() + "' has more than " + MAX_SCALE + " decimal places");
        }
        return Amount.of(value);
    }

    private static @Nullable String cell(List<String> cells, int position) {
        return position >= 0 && position < cells.size() ? cells.get(position) : null;
    }

    private static @Nullable String trimmed(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static @Nullable String cut(@Nullable String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }

    private static @Nullable String firstNonBlank(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }
}
