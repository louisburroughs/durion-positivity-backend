package com.positivity.accounting.internal.bankfeed.file.parser;

import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** The statement-file parsers by {@code formatCode} (SPEC §2.1, §4.3; story S3, #2302). */
@Component
public class StatementFileParsers {

    private final Map<String, StatementFileParser> byFormat;

    public StatementFileParsers(@NonNull List<StatementFileParser> parsers) {
        this.byFormat = parsers.stream()
                .collect(Collectors.toMap(p -> p.formatCode().toUpperCase(Locale.ROOT), Function.identity()));
    }

    /**
     * The parser for a format code.
     *
     * @throws BankRecException {@code VALIDATION_ERROR} on field {@code formatCode} for a code no parser
     *     reads (phase 1 reads {@code CSV} only)
     */
    public @NonNull StatementFileParser require(@Nullable String formatCode) {
        StatementFileParser parser =
                formatCode == null ? null : byFormat.get(formatCode.trim().toUpperCase(Locale.ROOT));
        if (parser == null) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "Unsupported formatCode " + formatCode,
                    "formatCode",
                    "one of " + byFormat.keySet().stream().sorted().toList());
        }
        return parser;
    }
}
