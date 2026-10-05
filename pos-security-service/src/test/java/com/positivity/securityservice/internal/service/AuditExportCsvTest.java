package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.dto.AuditLogEventDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("AuditExportCsv - RFC 4180 escaping and formula-injection neutralisation (#2408)")
class AuditExportCsvTest {

    @Test
    void plainValuesAreUnquoted() {
        assertThat(AuditExportCsv.cell("ROLE_ASSIGNED")).isEqualTo("ROLE_ASSIGNED");
    }

    @Test
    void nullAndEmptyAreEmptyCells() {
        assertThat(AuditExportCsv.cell(null)).isEmpty();
        assertThat(AuditExportCsv.cell("")).isEmpty();
    }

    @Test
    void commasQuotesAndLineBreaksAreQuotedWithInnerQuotesDoubled() {
        assertThat(AuditExportCsv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(AuditExportCsv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(AuditExportCsv.cell("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(AuditExportCsv.cell("line1\r\nline2")).isEqualTo("\"line1\r\nline2\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"=HYPERLINK(\"http://evil\")", "+1+1", "-2+3", "@SUM(A1)", "\tcmd", "\rcmd"})
    void formulaTriggersAreNeutralisedWithALeadingQuote(String value) {
        String cell = AuditExportCsv.cell(value);
        String unquoted = cell.startsWith("\"") ? cell.substring(1) : cell;
        assertThat(unquoted).startsWith("'" + value.charAt(0));
    }

    @Test
    void neutralisedFormulaWithACommaIsQuotedAfterThePrefix() {
        assertThat(AuditExportCsv.cell("=1,2")).isEqualTo("\"'=1,2\"");
    }

    @Test
    void formulaCharactersInsideAValueAreLeftAlone() {
        assertThat(AuditExportCsv.cell("a=b")).isEqualTo("a=b");
    }

    @Test
    void renderWritesTheHeaderAndOneCrlfTerminatedRowPerEvent() {
        AuditLogEventDto event = AuditLogEventDto.builder()
                .eventId(UUID.fromString("01960000-0000-7000-8000-000000000001"))
                .timestamp(Instant.parse("2026-10-01T08:00:00Z"))
                .eventType("ROLE_ASSIGNED")
                .actorId("=cmd")
                .entityId("u1")
                .entityType("USER")
                .oldValue("{}")
                .newValue("{\"a\":1,\"b\":2}")
                .build();

        String csv = AuditExportCsv.render(List.of(event));

        assertThat(csv)
                .isEqualTo(AuditExportCsv.HEADER + "\r\n"
                        + "01960000-0000-7000-8000-000000000001,2026-10-01T08:00:00Z,ROLE_ASSIGNED,'=cmd,u1,USER,{},"
                        + "\"{\"\"a\"\":1,\"\"b\"\":2}\",\r\n");
    }
}
