package com.positivity.extractionworker.internal.sandbox;

import com.positivity.extractionworker.internal.enums.ReasonCode;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * CSV checks: a text file (no NUL bytes), UTF-8 with the byte-order mark stripped or else ISO-8859-1,
 * delimiter sniffed among comma, semicolon and tab from the first 20 lines, then the row, column and cell
 * limits. {@code pageCount} is the number of data rows: the non-empty rows after the first.
 */
final class CsvInspector {

    private static final char[] DELIMITERS = {',', ';', '\t'};
    private static final int SNIFF_LINES = 20;

    private CsvInspector() {}

    static SandboxResult inspect(Path input, SandboxLimits limits) throws IOException {
        byte[] bytes = Files.readAllBytes(input);
        for (byte b : bytes) {
            if (b == 0) {
                return SandboxResult.rejected(ReasonCode.TYPE_MISMATCH);
            }
        }
        String text = decode(bytes);
        char delimiter = sniffDelimiter(text);
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setIgnoreEmptyLines(true)
                .build();
        int records = 0;
        int nonEmpty = 0;
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            for (CSVRecord record : parser) {
                records++;
                if (records > limits.maxRows()) {
                    return SandboxResult.rejected(ReasonCode.RESOURCE_LIMIT_EXCEEDED);
                }
                if (record.size() > limits.maxColumns()) {
                    return SandboxResult.rejected(ReasonCode.RESOURCE_LIMIT_EXCEEDED);
                }
                boolean hasData = false;
                for (String cell : record) {
                    if (cell.length() > limits.maxCellChars()) {
                        return SandboxResult.rejected(ReasonCode.RESOURCE_LIMIT_EXCEEDED);
                    }
                    hasData |= !cell.isBlank();
                }
                if (hasData) {
                    nonEmpty++;
                }
            }
        } catch (UncheckedIOException | IllegalStateException | IllegalArgumentException e) {
            return SandboxResult.rejected(ReasonCode.UNREADABLE);
        }
        if (nonEmpty == 0) {
            return SandboxResult.rejected(ReasonCode.UNREADABLE);
        }
        return SandboxResult.accepted(nonEmpty - 1);
    }

    /** UTF-8 when the bytes are valid UTF-8 (byte-order mark removed), else ISO-8859-1. */
    static String decode(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /** The delimiter with the most occurrences in the first lines; comma on a tie or when none appears. */
    static char sniffDelimiter(String text) {
        int[] counts = new int[DELIMITERS.length];
        int lines = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length() && lines < SNIFF_LINES; i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '\n') {
                lines++;
            } else if (!quoted) {
                for (int d = 0; d < DELIMITERS.length; d++) {
                    if (c == DELIMITERS[d]) {
                        counts[d]++;
                    }
                }
            }
        }
        int best = 0;
        for (int d = 1; d < DELIMITERS.length; d++) {
            if (counts[d] > counts[best]) {
                best = d;
            }
        }
        return DELIMITERS[best];
    }
}
