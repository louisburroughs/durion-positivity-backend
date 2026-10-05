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

    @NonNull
    static String render(@NonNull List<AuditLogEventDto> events) {
        StringBuilder out = new StringBuilder(HEADER).append(LINE_END);
        for (AuditLogEventDto event : events) {
            out.append(cell(
                            event.getEventId() == null
                                    ? null
                                    : event.getEventId().toString()))
                    .append(',')
                    .append(cell(
                            event.getTimestamp() == null
                                    ? null
                                    : event.getTimestamp().toString()))
                    .append(',')
                    .append(cell(event.getEventType()))
                    .append(',')
                    .append(cell(event.getActorId()))
                    .append(',')
                    .append(cell(event.getEntityId()))
                    .append(',')
                    .append(cell(event.getEntityType()))
                    .append(',')
                    .append(cell(event.getOldValue()))
                    .append(',')
                    .append(cell(event.getNewValue()))
                    .append(',')
                    .append(cell(event.getContext()))
                    .append(LINE_END);
        }
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
