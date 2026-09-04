package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.exception.InvalidToolArgumentException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
        assertThatExceptionOfType(InvalidToolArgumentException.class)
                .isThrownBy(() -> tool.resolveDateWindow("SOMETIME_SOON", "MONTH", 1, null))
                .withMessageContaining("ROLLING, CURRENT_TO_DATE, PRIOR_COMPLETE, CALENDAR_SPAN");
    }

    @Test
    @DisplayName("resolveDateWindow rejects an unrecognized unit with a self-correcting message")
    void resolveDateWindow_rejectsUnrecognizedUnit() {
        assertThatExceptionOfType(InvalidToolArgumentException.class)
                .isThrownBy(() -> tool.resolveDateWindow("ROLLING", "FORTNIGHT", 1, null))
                .withMessageContaining("DAY, WEEK, MONTH, QUARTER, YEAR");
    }

    @Test
    @DisplayName("resolveDateWindow rejects an unrecognized comparison with a self-correcting message")
    void resolveDateWindow_rejectsUnrecognizedComparison() {
        assertThatExceptionOfType(InvalidToolArgumentException.class)
                .isThrownBy(() -> tool.resolveDateWindow("ROLLING", "MONTH", 1, "LAST_QUARTER"))
                .withMessageContaining("NONE, PRIOR_PERIOD, YEAR_EARLIER");
    }

    @Test
    @DisplayName("resolveDateWindow propagates the resolver's DAY-with-a-calendar-shape rejection")
    void resolveDateWindow_propagatesDayCalendarShapeRejection() {
        assertThatExceptionOfType(InvalidToolArgumentException.class)
                .isThrownBy(() -> tool.resolveDateWindow("CALENDAR_SPAN", "DAY", 90, null))
                .withMessageContaining("DAY has no calendar form");
    }

    /**
     * #1684: the shape is the stage that fails, and once a window leaves this tool it is two dates
     * in some other tool's arguments — a window resolved under the wrong shape is byte-identical to
     * a correct one from there on. Logging the classification alongside the dates it produced is
     * what lets the per-stage eval (#1682) assert on it instead of a human reading the answer prose
     * for the word "rolling". This pins the argument names the eval parses, not only that something
     * was logged.
     */
    @Test
    @DisplayName("resolveDateWindow logs the classified shape alongside the dates it produced")
    void resolveDateWindow_logsTheClassifiedShapeAndResolvedDates() {
        try (LogCapture captured = new LogCapture()) {
            tool.resolveDateWindow("CALENDAR_SPAN", "MONTH", 12, "YEAR_EARLIER");

            assertThat(captured.events()).hasSize(1);
            ILoggingEvent logged = captured.events().getFirst();
            assertThat(logged.getLevel()).isEqualTo(Level.INFO);
            assertThat(logged.getFormattedMessage())
                    .isEqualTo("MCP date window resolved shape=CALENDAR_SPAN unit=MONTH count=12 "
                            + "comparison=YEAR_EARLIER startDate=2025-09-01 endDate=2026-08-31 "
                            + "comparisonStartDate=2024-09-01 comparisonEndDate=2025-08-31");
        }
    }

    /**
     * The line has to be readable without the caller's question, so an absent comparison logs the
     * NONE it resolved to rather than a null the eval would have to interpret.
     */
    @Test
    @DisplayName("resolveDateWindow logs comparison=NONE when no comparison was requested")
    void resolveDateWindow_logsNoneWhenNoComparisonRequested() {
        try (LogCapture captured = new LogCapture()) {
            tool.resolveDateWindow("PRIOR_COMPLETE", "MONTH", 1, null);

            assertThat(captured.events().getFirst().getFormattedMessage())
                    .contains("comparison=NONE")
                    .doesNotContain("comparisonStartDate");
        }
    }

    /**
     * The comparison window's dates, not just the comparison mode. q09, q12 and q15 — the questions
     * #1684 exists for — are paired comparisons, so the window a grader checks is often the
     * comparison one. Logging {@code comparison=PRIOR_PERIOD} alone would name the mode and drop the
     * value, leaving the graded half out of the trace.
     */
    @Test
    @DisplayName("resolveDateWindow logs the comparison window's own dates, not just its mode")
    void resolveDateWindow_logsTheComparisonWindowDates() {
        try (LogCapture captured = new LogCapture()) {
            tool.resolveDateWindow("CALENDAR_SPAN", "MONTH", 6, "PRIOR_PERIOD");

            assertThat(captured.events().getFirst().getFormattedMessage())
                    .contains("startDate=2026-03-01 endDate=2026-08-31")
                    .contains("comparisonStartDate=2025-09-01 comparisonEndDate=2026-02-28");
        }
    }

    /**
     * A rejected classification resolved no window, so there is nothing to log — and logging a
     * shape the resolver refused would put a window in the eval's trace that never existed.
     */
    @Test
    @DisplayName("resolveDateWindow logs nothing when the classification is rejected")
    void resolveDateWindow_logsNothingWhenRejected() {
        try (LogCapture captured = new LogCapture()) {
            assertThatExceptionOfType(InvalidToolArgumentException.class)
                    .isThrownBy(() -> tool.resolveDateWindow("CALENDAR_SPAN", "DAY", 90, null));

            assertThat(captured.events()).isEmpty();
        }
    }

    /**
     * Guards the capture helper itself. Every other test here would pass just as well with a helper
     * that clears the logger's level on the way out instead of restoring it — the damage from that
     * lands on some unrelated test later in the JVM, which is exactly the kind of failure nobody
     * traces back to here. Asserting the round trip from a deliberately non-default level is what
     * makes the isolation a property rather than an intention.
     */
    @Test
    @DisplayName("the log-capture helper leaves the logger's level exactly as it found it")
    void logCapture_restoresThePreviousLoggerLevel() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DateWindowFacadeTool.class);
        Level original = logger.getLevel();
        logger.setLevel(Level.WARN);
        try {
            try (LogCapture captured = new LogCapture()) {
                assertThat(logger.getLevel()).isEqualTo(Level.INFO);
                assertThat(captured.events()).isEmpty();
            }

            assertThat(logger.getLevel()).isEqualTo(Level.WARN);
        } finally {
            logger.setLevel(original);
        }
    }

    /**
     * Captures {@link DateWindowFacadeTool}'s log output for the duration of a try-with-resources
     * block, leaving the logger exactly as it was found.
     *
     * <p>The obvious version of this helper — set INFO on the way in, {@code setLevel(null)} on the
     * way out — does not restore, it resets: {@code null} means "inherit from the parent", which is
     * only the right answer when the level happened to be unset already. A logger configured
     * elsewhere (a profile's {@code logging.level.com.positivity.mcp}, a future test that pins DEBUG
     * to assert something quieter) would be silently cleared by the first test here that ran, and
     * the damage would show up as an order-dependent failure somewhere else in the JVM. Capturing
     * the previous level and putting it back costs one field and removes the whole class of
     * problem. Detaching the appender and stopping it likewise: an appender left attached keeps
     * collecting events from every later test that logs on this logger.
     */
    private static final class LogCapture implements AutoCloseable {

        private final ch.qos.logback.classic.Logger logger;
        private final Level previousLevel;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private LogCapture() {
            this.logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DateWindowFacadeTool.class);
            this.previousLevel = logger.getLevel();
            logger.setLevel(Level.INFO);
            appender.start();
            logger.addAppender(appender);
        }

        private List<ILoggingEvent> events() {
            return appender.list;
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    // ── resolveNamedPeriod (#1684) ───────────────────────────────────────────

    @Test
    @DisplayName("resolveNamedPeriod returns the named period's whole span with shape ABSOLUTE")
    void resolveNamedPeriod_returnsAbsoluteWindow() {
        JsonNode node = parse(tool.resolveNamedPeriod("2025"));

        assertThat(node.get("startDate").asText()).isEqualTo("2025-01-01");
        assertThat(node.get("endDate").asText()).isEqualTo("2025-12-31");
        assertThat(node.get("shape").asText()).isEqualTo("ABSOLUTE");
        assertThat(node.get("statement").asText()).contains("named calendar year 2025");
        assertThat(node.has("comparison")).isFalse();
    }

    @Test
    @DisplayName("resolveNamedPeriod handles months and quarters")
    void resolveNamedPeriod_handlesMonthsAndQuarters() {
        assertThat(parse(tool.resolveNamedPeriod("2026-07")).get("endDate").asText())
                .isEqualTo("2026-07-31");
        assertThat(parse(tool.resolveNamedPeriod("2026-Q3")).get("startDate").asText())
                .isEqualTo("2026-07-01");
        assertThat(parse(tool.resolveNamedPeriod("2026-Q3")).get("endDate").asText())
                .isEqualTo("2026-09-30");
    }

    @Test
    @DisplayName("resolveNamedPeriod logs the shape and dates, so the trace records which reading was used")
    void resolveNamedPeriod_logsResolution() {
        try (LogCapture capture = new LogCapture()) {
            tool.resolveNamedPeriod("2026-Q3");

            assertThat(capture.events())
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("shape=ABSOLUTE")
                            .contains("period=2026-Q3")
                            .contains("startDate=2026-07-01")
                            .contains("endDate=2026-09-30"));
        }
    }

    @Test
    @DisplayName("resolveNamedPeriod rejects a relative phrase and points at resolveDateWindow")
    void resolveNamedPeriod_rejectsRelativePhrase() {
        assertThatThrownBy(() -> tool.resolveNamedPeriod("last month"))
                .isInstanceOf(InvalidToolArgumentException.class)
                .hasMessageContaining("resolveDateWindow");
    }
}
