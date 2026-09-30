package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.event.AgentCacheInvalidationEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ScopeGraphHolderTest {

    private static final ScopeGraphProperties SHADOW =
            new ScopeGraphProperties(ScopeGraphProperties.Mode.SHADOW, List.of(), 0, 0, 0);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    /** Runs queued builds on the test thread, when the test says so. */
    private final ArrayDeque<Runnable> queued = new ArrayDeque<>();

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private ExecutorService realExecutor;

    @BeforeEach
    void attachLogs() {
        logs.start();
        logger().addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger().detachAppender(logs);
        if (realExecutor != null) {
            realExecutor.shutdownNow();
        }
    }

    private static ch.qos.logback.classic.Logger logger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ScopeGraphHolder.class);
    }

    private static ScopeGraphBuildResult result(String entity, ScopeGraphFinding... findings) {
        ScopeGraph.Builder builder = ScopeGraph.builder();
        builder.node(NodeType.ENTITY, entity);
        return new ScopeGraphBuildResult(builder.build(Instant.parse("2026-09-30T12:00:00Z")), List.of(findings));
    }

    private void runQueued() {
        while (!queued.isEmpty()) {
            queued.poll().run();
        }
    }

    @Test
    @DisplayName("the holder starts with the empty graph and swaps in a built one, off the caller's thread")
    void swapsInBuiltGraph() {
        ScopeGraphHolder holder = new ScopeGraphHolder(SHADOW, () -> result("workorder"), queued::add, meters);

        assertThat(holder.current()).isSameAs(ScopeGraph.empty());

        holder.rebuild();
        // Nothing ran on the caller's thread: the build is only queued.
        assertThat(holder.current()).isSameAs(ScopeGraph.empty());
        assertThat(queued).hasSize(1);

        runQueued();
        assertThat(holder.current().entityOptions()).containsExactly("workorder");
    }

    @Test
    @DisplayName("a successful build records metrics and logs one INFO line with counts and hash")
    void metricsAndLogLine() {
        ScopeGraphHolder holder = new ScopeGraphHolder(
                SHADOW,
                () -> result("workorder", new ScopeGraphFinding(ScopeGraphFinding.Kind.DISCOVERED_TOOL_UNMAPPED, "t")),
                queued::add,
                meters);

        holder.rebuild();
        runQueued();

        assertThat(meters.get("mcp.scope_graph.build.duration").timer().count()).isEqualTo(1);
        assertThat(meters.get("mcp.scope_graph.build.failures").counter().count())
                .isZero();
        assertThat(meters.get("mcp.scope_graph.nodes").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("mcp.scope_graph.edges").gauge().value()).isZero();
        assertThat(meters.get("mcp.scope_graph.unmapped_tools").gauge().value()).isEqualTo(1.0);
        assertThat(logs.list)
                .filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.INFO)
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains(
                                "nodes=1", "edges=0", "hash=" + holder.current().contentHash(), "durationMs="));
        assertThat(logs.list)
                .filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).contains("DISCOVERED_TOOL_UNMAPPED=1"));
    }

    @Test
    @DisplayName("requests during a running build are coalesced into exactly one more build")
    void coalescesRequestsDuringABuild() throws InterruptedException {
        realExecutor = Executors.newSingleThreadExecutor();
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        Supplier<ScopeGraphBuildResult> build = () -> {
            int number = builds.incrementAndGet();
            if (number == 1) {
                firstStarted.countDown();
                await(releaseFirst);
            } else {
                secondDone.countDown();
            }
            return result("build-" + number);
        };
        ScopeGraphHolder holder = new ScopeGraphHolder(SHADOW, build, realExecutor, meters);

        holder.rebuild();
        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        // Five triggers arrive while the first build is still running.
        for (int i = 0; i < 5; i++) {
            holder.rebuild();
        }
        releaseFirst.countDown();
        assertThat(secondDone.await(5, TimeUnit.SECONDS)).isTrue();
        realExecutor.shutdown();
        assertThat(realExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(builds).hasValue(2);
        assertThat(holder.current().entityOptions()).containsExactly("build-2");
    }

    @Test
    @DisplayName("once a build has settled, the next request builds again")
    void rebuildsAfterSettling() {
        AtomicInteger builds = new AtomicInteger();
        ScopeGraphHolder holder =
                new ScopeGraphHolder(SHADOW, () -> result("build-" + builds.incrementAndGet()), queued::add, meters);

        holder.rebuild();
        runQueued();
        holder.rebuild();
        runQueued();

        assertThat(builds).hasValue(2);
        assertThat(holder.current().entityOptions()).containsExactly("build-2");
    }

    @Test
    @DisplayName("a build that throws keeps the previous snapshot and counts a failure")
    void failureKeepsPreviousSnapshot() {
        AtomicInteger builds = new AtomicInteger();
        ScopeGraphHolder holder = new ScopeGraphHolder(
                SHADOW,
                () -> {
                    if (builds.incrementAndGet() == 2) {
                        throw new EntityLexiconException("entity 'invoice': 'domain' is missing or not a string");
                    }
                    return result("build-" + builds.get());
                },
                queued::add,
                meters);

        holder.rebuild();
        runQueued();
        ScopeGraph first = holder.current();
        holder.rebuild();
        runQueued();

        assertThat(holder.current()).isSameAs(first);
        assertThat(meters.get("mcp.scope_graph.build.failures").counter().count())
                .isEqualTo(1.0);
        assertThat(meters.get("mcp.scope_graph.build.duration").timer().count()).isEqualTo(1);

        // The failure did not wedge the holder: the next trigger builds again.
        holder.rebuild();
        runQueued();
        assertThat(holder.current().entityOptions()).containsExactly("build-3");
    }

    @Test
    @DisplayName(
            "a build that dies with an Error is counted and logged like any other failure, and the holder is not wedged")
    void errorKeepsPreviousSnapshotAndIsCounted() {
        AtomicInteger builds = new AtomicInteger();
        ScopeGraphHolder holder = new ScopeGraphHolder(
                SHADOW,
                () -> {
                    int build = builds.incrementAndGet();
                    if (build == 2) {
                        // A catastrophically backtracking lexicon regex ends here, not in a RuntimeException.
                        throw new StackOverflowError();
                    }
                    if (build == 3) {
                        throw new NoClassDefFoundError("io/swagger/Missing");
                    }
                    return result("build-" + build);
                },
                queued::add,
                meters);

        holder.rebuild();
        runQueued();
        ScopeGraph first = holder.current();
        holder.rebuild();
        runQueued();
        holder.rebuild();
        runQueued();

        assertThat(holder.current()).isSameAs(first);
        assertThat(meters.get("mcp.scope_graph.build.failures").counter().count())
                .isEqualTo(2.0);
        assertThat(logs.list)
                .filteredOn(event -> event.getFormattedMessage().contains("Scope graph build failed"))
                .hasSize(2);

        holder.rebuild();
        runQueued();
        assertThat(holder.current().entityOptions()).containsExactly("build-4");
    }

    @Test
    @DisplayName("an out-of-memory error is not swallowed, and still does not wedge the holder")
    void outOfMemoryPropagates() {
        AtomicInteger builds = new AtomicInteger();
        ScopeGraphHolder holder = new ScopeGraphHolder(
                SHADOW,
                () -> {
                    if (builds.incrementAndGet() == 1) {
                        throw new OutOfMemoryError("simulated");
                    }
                    return result("build-" + builds.get());
                },
                queued::add,
                meters);

        holder.rebuild();
        assertThatThrownBy(this::runQueued).isInstanceOf(OutOfMemoryError.class);
        assertThat(meters.get("mcp.scope_graph.build.failures").counter().count())
                .isZero();

        holder.rebuild();
        runQueued();
        assertThat(holder.current().entityOptions()).containsExactly("build-2");
    }

    @Test
    @DisplayName("a first build that throws leaves the empty graph")
    void firstFailureLeavesEmptyGraph() {
        ScopeGraphHolder holder = new ScopeGraphHolder(
                SHADOW,
                () -> {
                    throw new IllegalStateException("No ScopeGraphCatalogReader is available in this profile");
                },
                queued::add,
                meters);

        holder.rebuild();
        runQueued();

        assertThat(holder.current()).isSameAs(ScopeGraph.empty());
        assertThat(meters.get("mcp.scope_graph.build.failures").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a tool-permission change triggers a rebuild; a system-prompt change does not")
    void permissionEventTriggersRebuild() {
        AtomicInteger builds = new AtomicInteger();
        ScopeGraphHolder holder =
                new ScopeGraphHolder(SHADOW, () -> result("build-" + builds.incrementAndGet()), queued::add, meters);

        holder.onAgentConfigurationChanged(AgentCacheInvalidationEvent.systemPromptChanged("base"));
        runQueued();
        assertThat(builds).hasValue(0);

        holder.onAgentConfigurationChanged(AgentCacheInvalidationEvent.toolPermissionChanged("WorkorderFacadeTool"));
        runQueued();
        assertThat(builds).hasValue(1);
    }

    @Test
    @DisplayName("mode off: no build, no source read, no meter and no log line, whatever the trigger")
    void offModeDoesNothing() {
        AtomicInteger builds = new AtomicInteger();
        ScopeGraphHolder holder = new ScopeGraphHolder(
                ScopeGraphProperties.off(), () -> result("build-" + builds.incrementAndGet()), queued::add, meters);

        holder.rebuild();
        holder.onAgentConfigurationChanged(AgentCacheInvalidationEvent.toolPermissionChanged("WorkorderFacadeTool"));
        runQueued();

        assertThat(queued).isEmpty();
        assertThat(builds).hasValue(0);
        assertThat(holder.current()).isSameAs(ScopeGraph.empty());
        assertThat(meters.getMeters()).isEmpty();
        assertThat(logs.list).isEmpty();
    }

    @Test
    @DisplayName("a rejected execution (shutdown) is swallowed and does not wedge later requests")
    void rejectedExecutionIsSwallowed() {
        AtomicInteger builds = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        ScopeGraphHolder holder = new ScopeGraphHolder(
                SHADOW,
                () -> result("build-" + builds.incrementAndGet()),
                command -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new java.util.concurrent.RejectedExecutionException("shutting down");
                    }
                    queued.add(command);
                },
                meters);

        holder.rebuild();
        holder.rebuild();
        runQueued();

        assertThat(builds).hasValue(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch was never released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
