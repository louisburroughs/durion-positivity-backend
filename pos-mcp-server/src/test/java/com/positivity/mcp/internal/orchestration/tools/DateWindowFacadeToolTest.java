package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DateWindowFacadeTool} (#1675): the {@code resolveDateWindow} JSON shape,
 * {@link Clock}-driven date resolution, and pinned statement wording. The arithmetic itself is
 * covered exhaustively by {@link DateWindowResolverTest}; these tests only check the tool's own
 * job — parsing string arguments, using the shared clock, and shaping the JSON envelope.
 */
class DateWindowFacadeToolTest {

    private static final String TODAY = "2026-09-03";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse(TODAY + "T10:15:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DateWindowFacadeTool tool;

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tool result is not valid JSON: " + json, exception);
        }
    }

    @BeforeEach
    void setUp() {
        tool = new DateWindowFacadeTool(FIXED_CLOCK);
    }

    @Test
    @DisplayName("resolveDateWindow uses the shared clock and returns startDate/endDate/shape/statement")
    void resolveDateWindow_returnsStartEndShapeAndStatement() {
        // q01 (analytics-gate QUESTIONS.json): "last month" as of 2026-09-03 -> 2026-08-01..2026-08-31.
        JsonNode result = parse(tool.resolveDateWindow("PRIOR_COMPLETE", "MONTH", 1, null));

        assertThat(result.get("startDate").asText()).isEqualTo("2026-08-01");
        assertThat(result.get("endDate").asText()).isEqualTo("2026-08-31");
        assertThat(result.get("shape").asText()).isEqualTo("PRIOR_COMPLETE");
        assertThat(result.get("statement").asText())
                .isEqualTo("prior complete: 2026-08-01 to 2026-08-31 — the most recently completed month "
                        + "(August 2026)");
    }

    @Test
    @DisplayName("resolveDateWindow omits the comparison field when comparison is NONE or absent")
    void resolveDateWindow_omitsComparisonWhenNotRequested() {
        JsonNode withoutArg = parse(tool.resolveDateWindow("CALENDAR_SPAN", "MONTH", 6, null));
        JsonNode explicitNone = parse(tool.resolveDateWindow("CALENDAR_SPAN", "MONTH", 6, "NONE"));

        assertThat(withoutArg.has("comparison")).isFalse();
        assertThat(explicitNone.has("comparison")).isFalse();
    }

    @Test
    @DisplayName("resolveDateWindow includes comparison.startDate/endDate/statement when requested")
    void resolveDateWindow_includesComparisonWhenRequested() {
        // q15: "over the last six months compared with the same six months last year", calendar-shape
        // primary per the #1670 mixed-comparison precedence, YEAR_EARLIER comparison.
        JsonNode result = parse(tool.resolveDateWindow("CALENDAR_SPAN", "MONTH", 6, "YEAR_EARLIER"));

        assertThat(result.get("startDate").asText()).isEqualTo("2026-03-01");
        assertThat(result.get("endDate").asText()).isEqualTo("2026-08-31");
        assertThat(result.has("comparison")).isTrue();
        JsonNode comparison = result.get("comparison");
        assertThat(comparison.get("startDate").asText()).isEqualTo("2025-03-01");
        assertThat(comparison.get("endDate").asText()).isEqualTo("2025-08-31");
        assertThat(comparison.get("statement").asText())
                .isEqualTo("year earlier: 2025-03-01 to 2025-08-31 — the same span one year earlier");
    }

    @Test
    @DisplayName("resolveDateWindow is case-insensitive and tolerates surrounding whitespace on its arguments")
    void resolveDateWindow_isCaseInsensitiveOnArguments() {
        JsonNode result = parse(tool.resolveDateWindow(" rolling ", " day ", 7, " none "));

        assertThat(result.get("shape").asText()).isEqualTo("ROLLING");
        assertThat(result.get("startDate").asText()).isEqualTo("2026-08-28");
        assertThat(result.get("endDate").asText()).isEqualTo("2026-09-03");
    }

    @Test
    @DisplayName("resolveDateWindow rejects an unrecognized shape with a self-correcting message")
    void resolveDateWindow_rejectsUnrecognizedShape() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> tool.resolveDateWindow("SOMETIME_SOON", "MONTH", 1, null))
                .withMessageContaining("ROLLING, CURRENT_TO_DATE, PRIOR_COMPLETE, CALENDAR_SPAN");
    }

    @Test
    @DisplayName("resolveDateWindow rejects an unrecognized unit with a self-correcting message")
    void resolveDateWindow_rejectsUnrecognizedUnit() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> tool.resolveDateWindow("ROLLING", "FORTNIGHT", 1, null))
                .withMessageContaining("DAY, WEEK, MONTH, QUARTER, YEAR");
    }

    @Test
    @DisplayName("resolveDateWindow rejects an unrecognized comparison with a self-correcting message")
    void resolveDateWindow_rejectsUnrecognizedComparison() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> tool.resolveDateWindow("ROLLING", "MONTH", 1, "LAST_QUARTER"))
                .withMessageContaining("NONE, PRIOR_PERIOD, YEAR_EARLIER");
    }

    @Test
    @DisplayName("resolveDateWindow propagates the resolver's DAY-with-a-calendar-shape rejection")
    void resolveDateWindow_propagatesDayCalendarShapeRejection() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> tool.resolveDateWindow("CALENDAR_SPAN", "DAY", 90, null))
                .withMessageContaining("DAY has no calendar form");
    }
}
