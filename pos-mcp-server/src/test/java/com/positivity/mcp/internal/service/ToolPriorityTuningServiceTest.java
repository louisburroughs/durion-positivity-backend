package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for {@link ToolPriorityTuningService} (Gate 7, #1195).
 *
 * <p>
 * The service is {@code @Profile("!test")} so it is constructed directly.
 * {@code @Scheduled} does not fire in unit tests; {@code tuneToolPriorities()}
 * is invoked explicitly.
 */
@ExtendWith(MockitoExtension.class)
class ToolPriorityTuningServiceTest {

    private static final String PASSING_EVAL = "{\"thresholds\":{\"passed\":true}}";
    private static final String FAILING_EVAL =
            "{\"thresholds\":{\"passed\":false,\"failures\":[\"hit_at_5 0.5 < floor 0.86\"]}}";

    @Mock
    private JdbcTemplate jdbcTemplate;

    private final Clock clock = Clock.fixed(Instant.parse("2026-04-13T02:00:00Z"), ZoneOffset.UTC);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @TempDir
    Path tempDir;

    private static final UUID TOOL_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private ToolPriorityTuningService service;

    @BeforeEach
    void setUp() {
        service = newService("shadow", null, tempDir.resolve("absent.json"));
    }

    private ToolPriorityTuningService newService(String mode, String legacyEnabled, Path evalPath) {
        return new ToolPriorityTuningService(
                jdbcTemplate, clock, meterRegistry, mode, legacyEnabled, evalPath.toString(), 48L);
    }

    private Path passingFreshEval() throws IOException {
        // The fixed clock is in the past relative to the real filesystem mtime, so a freshly
        // written file is always inside the freshness window.
        Path evalFile = tempDir.resolve("baseline-live-python.json");
        Files.writeString(evalFile, PASSING_EVAL);
        return evalFile;
    }

    @SuppressWarnings("unchecked")
    private void stubOneStatsRow() throws SQLException {
        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        when(resultSet.getObject("tool_id", UUID.class)).thenReturn(TOOL_ID_1);
        when(resultSet.getDouble("success_rate")).thenReturn(0.9);
        when(resultSet.getDouble("avg_latency")).thenReturn(150.0);
        when(resultSet.getDouble("fallback_rate")).thenReturn(0.1);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Timestamp.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        when(jdbcTemplate.queryForObject(anyString(), eq(Double.class), eq(TOOL_ID_1)))
                .thenReturn(0.5);
    }

    // ------------------------------------------------------------------
    // Mode parsing + legacy compatibility
    // ------------------------------------------------------------------

    @Test
    @DisplayName("mode defaults to off when neither mcp.tuning.mode nor legacy flag is set")
    void modeResolution_defaultOff() {
        assertThat(newService("", null, tempDir).effectiveMode()).isEqualTo(TuningMode.OFF);
    }

    @Test
    @DisplayName("mode strings parse case-insensitively")
    void modeResolution_parsesConfiguredValues() {
        assertThat(newService("off", null, tempDir).effectiveMode()).isEqualTo(TuningMode.OFF);
        assertThat(newService("SHADOW", null, tempDir).effectiveMode()).isEqualTo(TuningMode.SHADOW);
        assertThat(newService(" live ", null, tempDir).effectiveMode()).isEqualTo(TuningMode.LIVE);
    }

    @Test
    @DisplayName("legacy mcp.tuning.enabled=true with mode unset resolves to live (deprecated path)")
    void modeResolution_legacyEnabledTrue_isLive() {
        assertThat(newService("", "true", tempDir).effectiveMode()).isEqualTo(TuningMode.LIVE);
        assertThat(newService(null, "true", tempDir).effectiveMode()).isEqualTo(TuningMode.LIVE);
    }

    @Test
    @DisplayName("explicit mode wins over the legacy flag")
    void modeResolution_modeWinsOverLegacyFlag() {
        assertThat(newService("shadow", "true", tempDir).effectiveMode()).isEqualTo(TuningMode.SHADOW);
        assertThat(newService("off", "true", tempDir).effectiveMode()).isEqualTo(TuningMode.OFF);
    }

    @Test
    @DisplayName("legacy mcp.tuning.enabled=false stays off")
    void modeResolution_legacyEnabledFalse_isOff() {
        assertThat(newService("", "false", tempDir).effectiveMode()).isEqualTo(TuningMode.OFF);
    }

    @Test
    @DisplayName("invalid mcp.tuning.mode fails fast with a clear message")
    void modeResolution_invalidValue_throws() {
        assertThatThrownBy(() -> newService("sideways", null, tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mcp.tuning.mode")
                .hasMessageContaining("sideways");
    }

    @Test
    @DisplayName("mode=off skips the stats query entirely")
    void tuneToolPriorities_offMode_noQueries() {
        service = newService("off", null, tempDir);

        service.tuneToolPriorities();

        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Timestamp.class));
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // Stats query shape (shadow mode still runs the query)
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("tuneToolPriorities invokes the stats query on JdbcTemplate")
    void tuneToolPriorities_queryCalledWithSevenDayWindow() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Timestamp.class)))
                .thenReturn(List.of());

        service.tuneToolPriorities();

        verify(jdbcTemplate, times(1)).query(anyString(), any(RowMapper.class), any(Timestamp.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("tuneToolPriorities does not call update when stats query returns no rows")
    void tuneToolPriorities_noTools_noUpdates() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Timestamp.class)))
                .thenReturn(List.of());

        service.tuneToolPriorities();

        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("tuneToolPriorities query filters out null tool_id rows")
    void tuneToolPriorities_statsQueryContainsToolIdNotNullFilter() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Timestamp.class)))
                .thenReturn(List.of());

        service.tuneToolPriorities();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).query(sqlCaptor.capture(), any(RowMapper.class), any(Timestamp.class));
        assertThat(sqlCaptor.getValue()).contains("tool_id IS NOT NULL");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("tuneToolPriorities query filters out selection-only rows (executionTimeMs < 0)")
    void tuneToolPriorities_statsQueryContainsExecutionTimeMsFilter() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Timestamp.class)))
                .thenReturn(List.of());

        service.tuneToolPriorities();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).query(sqlCaptor.capture(), any(RowMapper.class), any(Timestamp.class));
        assertThat(sqlCaptor.getValue()).contains("execution_time_ms >= 0");
    }

    // ------------------------------------------------------------------
    // Shadow mode: propose, never write
    // ------------------------------------------------------------------

    @Test
    @DisplayName("shadow mode computes proposals but never writes, and counts them with mode=shadow")
    void tuneToolPriorities_shadowMode_doesNotWrite() throws SQLException {
        stubOneStatsRow();
        service = newService("shadow", null, tempDir.resolve("absent.json"));

        service.tuneToolPriorities();

        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
        assertThat(meterRegistry
                        .counter("mcp.tuning.proposals", "mode", "shadow")
                        .count())
                .isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // Live mode: eval promotion gate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("live mode with a fresh passing eval baseline writes the clamped priority")
    void tuneToolPriorities_liveMode_freshPassingEval_writes() throws SQLException, IOException {
        stubOneStatsRow();
        service = newService("live", null, passingFreshEval());

        service.tuneToolPriorities();

        ArgumentCaptor<Double> priorityCaptor = ArgumentCaptor.forClass(Double.class);
        verify(jdbcTemplate, times(1)).update(anyString(), priorityCaptor.capture(), any(), eq(TOOL_ID_1));
        assertThat(priorityCaptor.getValue())
                .as("computed new priority must be within the clamped range [0.1, 1.0]")
                .isBetween(0.1, 1.0);
        assertThat(meterRegistry.counter("mcp.tuning.proposals", "mode", "live").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("legacy mcp.tuning.enabled=true behaves as live (writes with a passing fresh eval)")
    void tuneToolPriorities_legacyEnabled_behavesAsLive() throws SQLException, IOException {
        stubOneStatsRow();
        service = newService("", "true", passingFreshEval());

        service.tuneToolPriorities();

        verify(jdbcTemplate, times(1)).update(anyString(), any(Double.class), any(), eq(TOOL_ID_1));
    }

    @Test
    @DisplayName("live mode with a missing eval baseline degrades to shadow (no write)")
    void tuneToolPriorities_liveMode_missingEval_degradesToShadow() throws SQLException {
        stubOneStatsRow();
        service = newService("live", null, tempDir.resolve("missing.json"));

        service.tuneToolPriorities();

        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
        assertThat(meterRegistry
                        .counter("mcp.tuning.proposals", "mode", "shadow")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("live mode with a stale eval baseline degrades to shadow (no write)")
    void tuneToolPriorities_liveMode_staleEval_degradesToShadow() throws SQLException, IOException {
        stubOneStatsRow();
        Path evalFile = tempDir.resolve("stale.json");
        Files.writeString(evalFile, PASSING_EVAL);
        // 100h before the fixed clock — outside the 48h freshness window.
        Files.setLastModifiedTime(evalFile, FileTime.from(Instant.now(clock).minus(100, ChronoUnit.HOURS)));
        service = newService("live", null, evalFile);

        service.tuneToolPriorities();

        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("live mode with thresholds.passed=false degrades to shadow (no write)")
    void tuneToolPriorities_liveMode_failedEval_degradesToShadow() throws SQLException, IOException {
        stubOneStatsRow();
        Path evalFile = tempDir.resolve("failed.json");
        Files.writeString(evalFile, FAILING_EVAL);
        service = newService("live", null, evalFile);

        service.tuneToolPriorities();

        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
        assertThat(meterRegistry
                        .counter("mcp.tuning.proposals", "mode", "shadow")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("live mode with an unparseable eval baseline degrades to shadow (no write)")
    void tuneToolPriorities_liveMode_unreadableEval_degradesToShadow() throws SQLException, IOException {
        stubOneStatsRow();
        Path evalFile = tempDir.resolve("garbage.json");
        Files.writeString(evalFile, "not json at all {");
        service = newService("live", null, evalFile);

        service.tuneToolPriorities();

        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
    }
}
