package com.positivity.bulkloader.internal.parser;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Header-driven parser for delimited text files. The delimiter is sniffed from the header line
 * (comma, tab, semicolon, or pipe) and columns are addressed by header name, so column order and
 * extra columns do not matter.
 */
@Component
public class DelimitedRecordFileParser implements RecordFileParser {

    private static final Set<String> EXTENSIONS = Set.of("csv", "tsv", "txt", "psv");
    private static final char[] CANDIDATE_DELIMITERS = {',', '\t', ';', '|'};
    private static final int HEADER_MARK_LIMIT = 1 << 20;

    @Override
    public boolean supports(@NonNull String fileName) {
        return EXTENSIONS.contains(FileNames.extension(fileName));
    }

    @Override
    @NonNull
    public RecordSource open(@NonNull InputStream content, @NonNull String fileName) {
        try {
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(content, StandardCharsets.UTF_8), HEADER_MARK_LIMIT);
            reader.mark(HEADER_MARK_LIMIT);
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                reader.close();
                throw new RecordFileParseException("Delimited file has no header line: " + fileName);
            }
            char delimiter = sniffDelimiter(headerLine);
            reader.reset();

            CSVReader csvReader = new CSVReaderBuilder(reader)
                    .withCSVParser(
                            new CSVParserBuilder().withSeparator(delimiter).build())
                    .build();
            String[] headerRow = csvReader.readNext();
            if (headerRow == null || headerRow.length == 0) {
                csvReader.close();
                throw new RecordFileParseException("Delimited file has no header row: " + fileName);
            }
            List<String> headers = normalizeHeaders(headerRow);
            return new CsvRecordSource(headers, csvReader);
        } catch (IOException | CsvValidationException e) {
            throw new RecordFileParseException("Failed to read delimited file: " + fileName, e);
        }
    }

    private char sniffDelimiter(String headerLine) {
        char best = ',';
        int bestCount = -1;
        for (char candidate : CANDIDATE_DELIMITERS) {
            int count = countOutsideQuotes(headerLine, candidate);
            if (count > bestCount) {
                bestCount = count;
                best = candidate;
            }
        }
        return best;
    }

    private int countOutsideQuotes(String line, char delimiter) {
        int count = 0;
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == delimiter && !inQuotes) {
                count++;
            }
        }
        return count;
    }

    private List<String> normalizeHeaders(String[] headerRow) {
        List<String> headers = new ArrayList<>(headerRow.length);
        for (int i = 0; i < headerRow.length; i++) {
            String header = headerRow[i] == null ? "" : headerRow[i].trim();
            if (i == 0 && header.startsWith("﻿")) {
                header = header.substring(1).trim();
            }
            headers.add(header.isEmpty() ? "column_" + (i + 1) : header);
        }
        return headers;
    }

    private static final class CsvRecordSource implements RecordSource {

        private final List<String> headers;
        private final CSVReader csvReader;

        private CsvRecordSource(List<String> headers, CSVReader csvReader) {
            this.headers = List.copyOf(headers);
            this.csvReader = csvReader;
        }

        @Override
        @NonNull
        public List<String> headers() {
            return headers;
        }

        @Override
        @Nullable
        public Map<String, String> nextRecord() {
            try {
                String[] row;
                while ((row = csvReader.readNext()) != null) {
                    if (isBlankRow(row)) {
                        continue;
                    }
                    Map<String, String> mapped = LinkedHashMap.newLinkedHashMap(headers.size());
                    for (int i = 0; i < headers.size() && i < row.length; i++) {
                        mapped.put(headers.get(i), row[i] == null ? "" : row[i].trim());
                    }
                    return mapped;
                }
                return null;
            } catch (IOException | CsvValidationException e) {
                throw new RecordFileParseException("Failed to read delimited record", e);
            }
        }

        private boolean isBlankRow(String[] row) {
            for (String cell : row) {
                if (cell != null && !cell.isBlank()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void close() {
            try {
                csvReader.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to close delimited file reader", e);
            }
        }
    }
}
