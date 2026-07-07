package com.positivity.bulkloader.internal.parser;

import java.io.InputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/** Selects the {@link RecordFileParser} for an uploaded file by its file name. */
@Component
@RequiredArgsConstructor
public class RecordFileParserRegistry {

    private final List<RecordFileParser> parsers;

    public boolean supports(@NonNull String fileName) {
        return parsers.stream().anyMatch(parser -> parser.supports(fileName));
    }

    @NonNull
    public RecordSource open(@NonNull InputStream content, @NonNull String fileName) {
        return parsers.stream()
                .filter(parser -> parser.supports(fileName))
                .findFirst()
                .orElseThrow(() -> new RecordFileParseException(
                        "Unsupported file type: %s (supported: csv, tsv, txt, psv, xlsx, xlsm, xls, json, yaml, yml, xml)"
                                .formatted(fileName)))
                .open(content, fileName);
    }
}
