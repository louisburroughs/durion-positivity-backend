package com.positivity.mcp.internal.service;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.positivity.mcp.internal.domain.ToolInvocationStats;
import com.positivity.mcp.internal.domain.ToolPriorityOverlay;
import com.positivity.mcp.internal.repository.ToolPriorityRepository;
import com.positivity.tenancy.PlatformScoped;
import com.positivity.tenancy.StaticTenantRegistry;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantAudited;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantIterator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Unit tests for {@link ToolPriorityTuningService} (Gate 7, #1195; per tenant with a global rollup,
 * ADR-0062 plan WS6).
 *
 * <p>The service is {@code @Profile("!test")} so it is constructed directly; {@code @Scheduled} does
 * not fire in unit tests, {@code tuneToolPriorities()} is invoked explicitly. The repository is an
 * in-memory fake that answers for whichever tenant is bound when it is called — the way row-level
 * security does on Postgres — so a test can hold two tenants' logs at once and check that each
 * overlay comes from its own tenant's log alone while the global row sums both.
 */
class ToolPriorityTuningServiceTest {

    private static final String PASSING_EVAL = "{\"thresholds\":{\"passed\":true}}";
    private static final String FAILING_EVAL =
            "{\"thresholds\":{\"passed\":false,\"failures\":[\"hit_at_5 0.5 < floor 0.86\"]}}";

    private static final UUID TOOL_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID UNKNOWN_TOOL = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private final Clock clock = Clock.fixed(Instant.parse("2026-04-13T02:00:00Z"), ZoneOffset.UTC);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final FakeToolPriorityRepository repository = new FakeToolPriorityRepository();

    @TempDir
    Path tempDir;

    private ToolPriorityTuningService service;

    @BeforeEach
    void setUp() {
        repository.globalPriority.put(TOOL_1, 0.5);
        repository.globalPriority.put(TOOL_2, 0.9);
        service = newService("shadow", null, tempDir.resolve("absent.json"), TENANT_A);
    }

    private ToolPriorityTuningService newService(String mode, String legacyEnabled, Path evalPath, UUID... tenants) {
        return new ToolPriorityTuningService(
                repository, clock, meterRegistry, mode, legacyEnabled, evalPath.toString(), 48L, tenants(tenants));
    }

    private Path passingFreshEval() throws IOException {
        // The fixed clock is in the past relative to the real filesystem mtime, so a freshly
        // written file is always inside the freshness window.
        Path evalFile = tempDir.resolve("baseline-live-python.json");
        Files.writeString(evalFile, PASSING_EVAL);
        return evalFile;
    }

    // ------------------------------------------------------------------
    // Mode parsing + legacy compatibility
    // ------------------------------------------------------------------

    @Test
    @DisplayName("mode defaults to off when neither mcp.tuning.mode nor legacy flag is set")
    void modeResolution_defaultOff() {
        assertThat(newService("", null, tempDir, TENANT_A).effectiveMode()).isEqualTo(TuningMode.OFF);
    }

    @Test
    @DisplayName("mode strings parse case-insensitively")
    void modeResolution_parsesConfiguredValues() {
        assertThat(newService("off", null, tempDir, TENANT_A).effectiveMode()).isEqualTo(TuningMode.OFF);
        assertThat(newService("SHADOW", null, tempDir, TENANT_A).effectiveMode())
                .isEqualTo(TuningMode.SHADOW);
        assertThat(newService(" live ", null, tempDir, TENANT_A).effectiveMode())
                .isEqualTo(TuningMode.LIVE);
    }

    @Test
    @DisplayName("legacy mcp.tuning.enabled=true with mode unset resolves to live (deprecated path)")
    void modeResolution_legacyEnabledTrue_isLive() {
        assertThat(newService("", "true", tempDir, TENANT_A).effectiveMode()).isEqualTo(TuningMode.LIVE);
        assertThat(newService(null, "true", tempDir, TENANT_A).effectiveMode()).isEqualTo(TuningMode.LIVE);
    }

    @Test
    @DisplayName("explicit mode wins over the legacy flag")
    void modeResolution_modeWinsOverLegacyFlag() {
        assertThat(newService("shadow", "true", tempDir, TENANT_A).effectiveMode())
                .isEqualTo(TuningMode.SHADOW);
        assertThat(newService("off", "true", tempDir, TENANT_A).effectiveMode()).isEqualTo(TuningMode.OFF);
    }

    @Test
    @DisplayName("legacy mcp.tuning.enabled=false stays off")
    void modeResolution_legacyEnabledFalse_isOff() {
        assertThat(newService("", "false", tempDir, TENANT_A).effectiveMode()).isEqualTo(TuningMode.OFF);
    }

    @Test
    @DisplayName("invalid mcp.tuning.mode fails fast with a clear message")
    void modeResolution_invalidValue_throws() {
        assertThatThrownBy(() -> newService("sideways", null, tempDir, TENANT_A))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mcp.tuning.mode")
                .hasMessageContaining("sideways");
    }

    @Test
    @DisplayName("mode=off visits no tenant and reads nothing")
    void tuneToolPriorities_offMode_noQueries() {
        repository.log(TENANT_A, stats(TOOL_1, 20, 20, 2_000, 0));
        service = newService("off", null, tempDir, TENANT_A);

        service.tuneToolPriorities();

        assertThat(repository.statsReadsByTenant).isEmpty();
        assertThat(repository.overlays(TENANT_A)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Per tenant, with the global rollup (ADR-0062 plan WS6)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("each tenant's overlay is computed from that tenant's own log; the global row from all tenants' logs")
    void tuneToolPriorities_perTenantOverlayAndGlobalRollup() throws IOException {
        ToolInvocationStats aStats = stats(TOOL_1, 20, 20, 2_000, 0); // fast and always succeeds
        ToolInvocationStats bStats = stats(TOOL_1, 20, 0, 38_000, 20); // slow, failing, falling back
        repository.log(TENANT_A, aStats);
        repository.log(TENANT_B, bStats);
        service = newService("live", null, passingFreshEval(), TENANT_A, TENANT_B);

        service.tuneToolPriorities();

        assertThat(repository.statsReadsByTenant)
                .as("the log is read once per tenant, each under its own binding")
                .containsExactly(TENANT_A, TENANT_B);
        double expectedA = ToolPriorityTuningService.propose(aStats, 0.5).newPriority();
        double expectedB = ToolPriorityTuningService.propose(bStats, 0.5).newPriority();
        assertThat(repository.overlays(TENANT_A).get(TOOL_1).priority())
                .as("tenant A's overlay: A's log alone, starting from the global row")
                .isCloseTo(expectedA, within(1e-9));
        assertThat(repository.overlays(TENANT_A).get(TOOL_1).avgLatencyMs()).isEqualTo(100);
        assertThat(repository.overlays(TENANT_B).get(TOOL_1).priority())
                .as("tenant B's overlay: B's log alone")
                .isCloseTo(expectedB, within(1e-9));
        assertThat(expectedA).isGreaterThan(0.5);
        assertThat(expectedB).isLessThan(0.5);

        double expectedGlobal =
                ToolPriorityTuningService.propose(aStats.plus(bStats), 0.5).newPriority();
        assertThat(repository.globalPriority.get(TOOL_1))
                .as("the global row: the sum of both tenants' aggregates")
                .isCloseTo(expectedGlobal, within(1e-9));
        assertThat(repository.globalLatency.get(TOOL_1)).isEqualTo(1_000);
        assertThat(repository.globalPriority.get(TOOL_2))
                .as("no history: untouched")
                .isEqualTo(0.9);
        assertThat(meterRegistry
                        .counter("mcp.tuning.proposals", "mode", "live", "scope", "tenant")
                        .count())
                .isEqualTo(2.0);
        assertThat(meterRegistry
                        .counter("mcp.tuning.proposals", "mode", "live", "scope", "global")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a tenant with no invocation history keeps no overlay and does not move the global row")
    void tuneToolPriorities_tenantWithoutHistory_keepsNoOverlay() throws IOException {
        ToolInvocationStats aStats = stats(TOOL_1, 20, 18, 3_000, 1);
        repository.log(TENANT_A, aStats);
        service = newService("live", null, passingFreshEval(), TENANT_A, TENANT_B);

        service.tuneToolPriorities();

        assertThat(repository.overlays(TENANT_B)).isEmpty();
        assertThat(repository.overlays(TENANT_A)).containsOnlyKeys(TOOL_1);
        assertThat(repository.globalPriority.get(TOOL_1))
                .isCloseTo(ToolPriorityTuningService.propose(aStats, 0.5).newPriority(), within(1e-9));
    }

    @Test
    @DisplayName("the minimum-call threshold applies per scope: two thin tenant logs tune the global row only")
    void tuneToolPriorities_thresholdPerScope() throws IOException {
        repository.log(TENANT_A, stats(TOOL_1, 6, 6, 600, 0));
        repository.log(TENANT_B, stats(TOOL_1, 6, 6, 600, 0));
        service = newService("live", null, passingFreshEval(), TENANT_A, TENANT_B);

        service.tuneToolPriorities();

        assertThat(repository.overlays(TENANT_A)).as("6 < 10 calls: no overlay").isEmpty();
        assertThat(repository.overlays(TENANT_B)).isEmpty();
        assertThat(repository.globalPriority.get(TOOL_1))
                .as("12 >= 10 calls across tenants")
                .isNotEqualTo(0.5);
    }

    @Test
    @DisplayName("an existing overlay drifts from its own previous value, not from the global row")
    void tuneToolPriorities_existingOverlayDriftsFromItself() throws IOException {
        ToolInvocationStats aStats = stats(TOOL_1, 20, 20, 2_000, 0);
        repository.log(TENANT_A, aStats);
        repository.overlays(TENANT_A).put(TOOL_1, new ToolPriorityOverlay(TOOL_1, 0.2, 400));
        service = newService("live", null, passingFreshEval(), TENANT_A);

        service.tuneToolPriorities();

        assertThat(repository.overlays(TENANT_A).get(TOOL_1).priority())
                .isCloseTo(ToolPriorityTuningService.propose(aStats, 0.2).newPriority(), within(1e-9));
    }

    @Test
    @DisplayName("a tool that left the catalog is skipped in both scopes")
    void tuneToolPriorities_unknownTool_skipped() throws IOException {
        repository.log(TENANT_A, stats(UNKNOWN_TOOL, 20, 20, 2_000, 0));
        service = newService("live", null, passingFreshEval(), TENANT_A);

        service.tuneToolPriorities();

        assertThat(repository.overlays(TENANT_A)).isEmpty();
        assertThat(repository.globalPriority).doesNotContainKey(UNKNOWN_TOOL);
    }

    @Test
    @DisplayName("shadow mode computes per-tenant and global proposals but writes neither, counted per scope")
    void tuneToolPriorities_shadowMode_doesNotWrite() {
        repository.log(TENANT_A, stats(TOOL_1, 20, 20, 2_000, 0));
        repository.log(TENANT_B, stats(TOOL_1, 20, 0, 38_000, 20));
        service = newService("shadow", null, tempDir.resolve("absent.json"), TENANT_A, TENANT_B);

        service.tuneToolPriorities();

        assertThat(repository.overlays(TENANT_A)).isEmpty();
        assertThat(repository.overlays(TENANT_B)).isEmpty();
        assertThat(repository.globalPriority.get(TOOL_1)).isEqualTo(0.5);
        assertThat(meterRegistry
                        .counter("mcp.tuning.proposals", "mode", "shadow", "scope", "tenant")
                        .count())
                .isEqualTo(2.0);
        assertThat(meterRegistry
                        .counter("mcp.tuning.proposals", "mode", "shadow", "scope", "global")
                        .count())
                .isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // Live mode: eval promotion gate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("legacy mcp.tuning.enabled=true behaves as live (writes with a passing fresh eval)")
    void tuneToolPriorities_legacyEnabled_behavesAsLive() throws IOException {
        repository.log(TENANT_A, stats(TOOL_1, 20, 20, 2_000, 0));
        service = newService("", "true", passingFreshEval(), TENANT_A);

        service.tuneToolPriorities();

        assertThat(repository.overlays(TENANT_A)).containsKey(TOOL_1);
        assertThat(repository.overlays(TENANT_A).get(TOOL_1).priority()).isBetween(0.1, 1.0);
    }

    @Test
    @DisplayName("live mode with a missing eval baseline degrades to shadow (no write)")
    void tuneToolPriorities_liveMode_missingEval_degradesToShadow() {
        repository.log(TENANT_A, stats(TOOL_1, 20, 20, 2_000, 0));
        service = newService("live", null, tempDir.resolve("missing.json"), TENANT_A);

        service.tuneToolPriorities();

        assertNothingWritten();
        assertThat(meterRegistry
                        .counter("mcp.tuning.proposals", "mode", "shadow", "scope", "tenant")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("live mode with a stale eval baseline degrades to shadow (no write)")
    void tuneToolPriorities_liveMode_staleEval_degradesToShadow() throws IOException {
        repository.log(TENANT_A, stats(TOOL_1, 20, 20, 2_000, 0));
        Path evalFile = tempDir.resolve("stale.json");
        Files.writeString(evalFile, PASSING_EVAL);
        // 100h before the fixed clock — outside the 48h freshness window.
        Files.setLastModifiedTime(evalFile, FileTime.from(Instant.now(clock).minus(100, ChronoUnit.HOURS)));
        service = newService("live", null, evalFile, TENANT_A);

        service.tuneToolPriorities();

        assertNothingWritten();
    }

    @Test
    @DisplayName("live mode with thresholds.passed=false degrades to shadow (no write)")
    void tuneToolPriorities_liveMode_failedEval_degradesToShadow() throws IOException {
        repository.log(TENANT_A, stats(TOOL_1, 20, 20, 2_000, 0));
        Path evalFile = tempDir.resolve("failed.json");
        Files.writeString(evalFile, FAILING_EVAL);
        service = newService("live", null, evalFile, TENANT_A);

        service.tuneToolPriorities();

        assertNothingWritten();
    }

    @Test
    @DisplayName("live mode with an unparseable eval baseline degrades to shadow (no write)")
    void tuneToolPriorities_liveMode_unreadableEval_degradesToShadow() throws IOException {
        repository.log(TENANT_A, stats(TOOL_1, 20, 20, 2_000, 0));
        Path evalFile = tempDir.resolve("garbage.json");
        Files.writeString(evalFile, "not json at all {");
        service = newService("live", null, evalFile, TENANT_A);

        service.tuneToolPriorities();

        assertNothingWritten();
    }

    // ------------------------------------------------------------------
    // Scheduler classification (ADR-0062 §3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the scheduled sweep is per tenant, the global rollup is platform-scoped, the log read is audited")
    void schedulerClassification() throws NoSuchMethodException {
        Method sweep = ToolPriorityTuningService.class.getMethod("tuneToolPriorities");
        assertThat(sweep.isAnnotationPresent(Scheduled.class)).isTrue();
        assertThat(sweep.isAnnotationPresent(PlatformScoped.class))
                .as("the sweep binds each tenant through TenantIterator; it is not a platform job")
                .isFalse();

        Method rollup = ToolPriorityTuningService.class.getDeclaredMethod(
                "recomputeGlobalPriorities", Map.class, boolean.class);
        assertThat(rollup.getAnnotation(PlatformScoped.class)).isNotNull();
        assertThat(rollup.getAnnotation(PlatformScoped.class).reason()).contains("mcp_tool");

        Method read = ToolPriorityTuningService.class.getDeclaredMethod("tenantInvocationStats", Instant.class);
        assertThat(read.getAnnotation(TenantAudited.class)).isNotNull();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void assertNothingWritten() {
        assertThat(repository.overlays(TENANT_A)).isEmpty();
        assertThat(repository.overlays(TENANT_B)).isEmpty();
        assertThat(repository.globalPriority).containsEntry(TOOL_1, 0.5).containsEntry(TOOL_2, 0.9);
    }

    private static ToolInvocationStats stats(
            UUID toolId, long calls, long successes, long latencySumMs, long fallbacks) {
        return new ToolInvocationStats(toolId, calls, successes, latencySumMs, fallbacks);
    }

    /** The active tenants of the sweep, in order (ADR-0062). */
    private static TenantIterator tenants(UUID... tenantIds) {
        TenancyProperties tenancy = new TenancyProperties();
        tenancy.setTenants(List.of(tenantIds));
        return new TenantIterator(new StaticTenantRegistry(tenancy));
    }

    /**
     * Answers for the tenant bound at call time, as row-level security does: the overlay and the
     * log of the bound tenant, nothing when unbound. The global catalog is shared.
     */
    static final class FakeToolPriorityRepository implements ToolPriorityRepository {

        final Map<UUID, List<ToolInvocationStats>> logByTenant = new HashMap<>();
        final Map<UUID, Map<UUID, ToolPriorityOverlay>> overlayByTenant = new HashMap<>();
        final Map<UUID, Double> globalPriority = new HashMap<>();
        final Map<UUID, Integer> globalLatency = new HashMap<>();
        final List<UUID> statsReadsByTenant = new ArrayList<>();

        void log(UUID tenantId, ToolInvocationStats... rows) {
            logByTenant.computeIfAbsent(tenantId, ignored -> new ArrayList<>()).addAll(List.of(rows));
        }

        Map<UUID, ToolPriorityOverlay> overlays(UUID tenantId) {
            return overlayByTenant.computeIfAbsent(tenantId, ignored -> new HashMap<>());
        }

        @Override
        public @NonNull Map<UUID, ToolPriorityOverlay> findOverlayForCurrentTenant() {
            return TenantContext.current().map(this::overlays).map(Map::copyOf).orElse(Map.of());
        }

        @Override
        public void upsertOverlay(@NonNull UUID toolId, double priority, int avgLatencyMs) {
            UUID tenantId = TenantContext.current()
                    .orElseThrow(() -> new IllegalStateException("unbound insert into a scoped table"));
            overlays(tenantId).put(toolId, new ToolPriorityOverlay(toolId, priority, avgLatencyMs));
        }

        @Override
        public @NonNull List<ToolInvocationStats> invocationStatsSince(@NonNull Instant cutoff) {
            Optional<UUID> tenantId = TenantContext.current();
            tenantId.ifPresent(statsReadsByTenant::add);
            return tenantId.map(id -> logByTenant.getOrDefault(id, List.of())).orElse(List.of());
        }

        @Override
        public @NonNull Optional<Double> findGlobalPriority(@NonNull UUID toolId) {
            return Optional.ofNullable(globalPriority.get(toolId));
        }

        @Override
        public void updateGlobalPriority(@NonNull UUID toolId, double priority, int avgLatencyMs) {
            globalPriority.put(toolId, priority);
            globalLatency.put(toolId, avgLatencyMs);
        }
    }
}
