package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.AuditLogEventDto;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * RFC 4180 CSV rendering of audit events for exports (#2408).
 *
 * <p>Audit values are caller-controlled text (entity ids, serialized old/new values), and the file
 * is meant to be opened in a spreadsheet, so a cell that would start with a formula trigger ({@code
 * = + - @}, or a tab or carriage return that some spreadsheets strip before evaluating) is prefixed
 * with a single quote, OWASP's CSV-injection mitigation. Quoting follows: a cell holding a comma,
 * quote, or line break is wrapped in double quotes with inner quotes doubled.
 */
final class AuditExportCsv {

    static final String HEADER = "eventId,timestamp,eventType,actorId,entityId,entityType,oldValue,newValue,context";

    private static final String LINE_END = "\r\n";

    private AuditExportCsv() {}

    /** The header line, CRLF-terminated. */
    @NonNull
    static String headerLine() {
        return HEADER + LINE_END;
    }

    /** One event as a CRLF-terminated line. */
    @NonNull
    static String row(@NonNull AuditLogEventDto event) {
        return String.join(
                        ",",
                        cell(
                                event.getEventId() == null
                                        ? null
                                        : event.getEventId().toString()),
                        cell(
                                event.getTimestamp() == null
                                        ? null
                                        : event.getTimestamp().toString()),
                        cell(event.getEventType()),
                        cell(event.getActorId()),
                        cell(event.getEntityId()),
                        cell(event.getEntityType()),
                        cell(event.getOldValue()),
                        cell(event.getNewValue()),
                        cell(event.getContext()))
                + LINE_END;
    }

    @NonNull
    static String render(@NonNull List<AuditLogEventDto> events) {
        StringBuilder out = new StringBuilder(headerLine());
        events.forEach(event -> out.append(row(event)));
        return out.toString();
    }

    /** One escaped, formula-neutralised cell; {@code null} is an empty cell. */
    @NonNull
    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String neutralised = startsWithFormulaTrigger(value) ? "'" + value : value;
        boolean needsQuoting = neutralised.indexOf(',') >= 0
                || neutralised.indexOf('"') >= 0
                || neutralised.indexOf('\n') >= 0
                || neutralised.indexOf('\r') >= 0;
        if (!needsQuoting) {
            return neutralised;
        }
        return '"' + neutralised.replace("\"", "\"\"") + '"';
    }

    private static boolean startsWithFormulaTrigger(String value) {
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }
}
