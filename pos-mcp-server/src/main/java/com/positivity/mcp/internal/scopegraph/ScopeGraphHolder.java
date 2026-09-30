package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.event.AgentCacheInvalidationEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ADR-0069 §4: holds the current {@link ScopeGraph} snapshot and rebuilds it when the catalog
 * changes.
 *
 * <p>{@link #rebuild()} never builds on the caller's thread. Requests are coalesced: one that
 * arrives while a build is running causes exactly one more build afterwards, however many arrive,
 * because that later build reads everything the earlier requests were about. The swap is a single
 * volatile write, so a reader sees the old snapshot or the new one, never a mix. A build that
 * throws leaves the previous snapshot in place (the empty graph before the first success): the graph
 * never fails startup and never fails a turn (spec §2.5).
 *
 * <p>With {@code mcp.scope-graph.mode: off} this bean is inert: no executor, no meters, no build, no
 * read of any source and no log line.
 */
@Component
public class ScopeGraphHolder {

    private static final Logger log = LoggerFactory.getLogger(ScopeGraphHolder.class);
    private static final String THREAD_NAME = "scope-graph-build";
    private static final int MAX_LOGGED_FINDINGS = 20;

    private final boolean enabled;
    private final Supplier<ScopeGraphBuildResult> build;

    @Nullable
    private final Executor executor;
    /** Non-null only when this bean created the executor and so has to stop it. */
    @Nullable
    private final ExecutorService ownedExecutor;

    @Nullable
    private final Timer buildDuration;

    @Nullable
    private final Counter buildFailures;

    private final AtomicInteger unmappedTools = new AtomicInteger();

    private final Object lock = new Object();
    private boolean running;
    private boolean pending;

    private volatile ScopeGraph current = ScopeGraph.empty();

    @Autowired
    public ScopeGraphHolder(
            @NonNull ScopeGraphProperties properties,
            @NonNull ScopeGraphSourceLoader sourceLoader,
            @NonNull Clock clock,
            @NonNull MeterRegistry meterRegistry) {
        this(properties, buildFrom(sourceLoader, clock), properties.enabled() ? newExecutor() : null, meterRegistry);
    }

    /** For tests: the build and the executor are supplied, so swap and coalescing can be driven. */
    ScopeGraphHolder(
            @NonNull ScopeGraphProperties properties,
            @NonNull Supplier<ScopeGraphBuildResult> build,
            @Nullable Executor executor,
            @NonNull MeterRegistry meterRegistry) {
        this.enabled = properties.enabled() && executor != null;
        this.build = build;
        this.executor = executor;
        this.ownedExecutor = executor instanceof OwnedExecutor owned ? owned.delegate() : null;
        if (enabled) {
            this.buildDuration = Timer.builder("mcp.scope_graph.build.duration")
                    .description("Time to build one scope-graph snapshot (ADR-0069)")
                    .register(meterRegistry);
            this.buildFailures = Counter.builder("mcp.scope_graph.build.failures")
                    .description("Scope-graph builds that threw; the previous snapshot was kept")
                    .register(meterRegistry);
            meterRegistry.gauge(
                    "mcp.scope_graph.nodes", this, holder -> holder.current().nodeCount());
            meterRegistry.gauge(
                    "mcp.scope_graph.edges", this, holder -> holder.current().edgeCount());
            meterRegistry.gauge("mcp.scope_graph.unmapped_tools", unmappedTools, AtomicInteger::get);
        } else {
            this.buildDuration = null;
            this.buildFailures = null;
        }
    }

    /** The current snapshot; {@link ScopeGraph#empty()} until the first successful build. */
    public @NonNull ScopeGraph current() {
        return current;
    }

    /**
     * Requests a rebuild and returns at once. Safe to call from any thread and any trigger (spec
     * §2.6); a no-op in mode {@code off}. Never throws.
     */
    public void rebuild() {
        if (!enabled || executor == null) {
            return;
        }
        synchronized (lock) {
            if (running) {
                pending = true;
                return;
            }
            running = true;
        }
        try {
            executor.execute(this::buildUntilSettled);
        } catch (RejectedExecutionException exception) {
            // Shutting down: there is no later turn to serve, so the request is dropped.
            synchronized (lock) {
                running = false;
                pending = false;
            }
        }
    }

    /**
     * ADR-0069 §4: a tool-permission grant or revoke changes {@code REQUIRES} edges. Declared like
     * the role-agent cache listeners for the same event: after the publishing transaction commits,
     * so the rebuild reads the committed rows, with fallback execution for a non-transactional
     * publisher. A system-prompt change touches nothing the graph holds and is ignored.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAgentConfigurationChanged(@NonNull AgentCacheInvalidationEvent event) {
        if (event.source() == AgentCacheInvalidationEvent.Source.TOOL_PERMISSION) {
            rebuild();
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    private void buildUntilSettled() {
        boolean settled = false;
        try {
            while (true) {
                buildOnce();
                synchronized (lock) {
                    if (!pending) {
                        running = false;
                        settled = true;
                        return;
                    }
                    pending = false;
                }
            }
        } finally {
            // An Error out of a build must not leave the holder believing a build is still running,
            // or no later trigger would ever start one.
            if (!settled) {
                synchronized (lock) {
                    running = false;
                    pending = false;
                }
            }
        }
    }

    private void buildOnce() {
        long startNanos = System.nanoTime();
        try {
            ScopeGraphBuildResult result = build.get();
            ScopeGraph graph = result.graph();
            current = graph;
            unmappedTools.set(result.unmappedTools());
            long elapsedNanos = System.nanoTime() - startNanos;
            if (buildDuration != null) {
                buildDuration.record(elapsedNanos, TimeUnit.NANOSECONDS);
            }
            log.info(
                    "Scope graph built: nodes={} edges={} hash={} durationMs={} findings={} unmappedTools={}",
                    graph.nodeCount(),
                    graph.edgeCount(),
                    graph.contentHash(),
                    TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                    result.findings().size(),
                    result.unmappedTools());
            warnOnFindings(result);
        } catch (RuntimeException exception) {
            if (buildFailures != null) {
                buildFailures.increment();
            }
            log.warn(
                    "Scope graph build failed; keeping the previous snapshot (hash={}): {}",
                    current.contentHash(),
                    exception.toString());
        }
    }

    /** Spec §2.5: at runtime a violation is logged once per build at WARN, never thrown. */
    private static void warnOnFindings(ScopeGraphBuildResult result) {
        if (result.findings().isEmpty()) {
            return;
        }
        Map<ScopeGraphFinding.Kind, Integer> counts = new TreeMap<>();
        result.findings().forEach(finding -> counts.merge(finding.kind(), 1, Integer::sum));
        log.warn(
                "Scope graph validation findings (degraded, not fatal): {}; first strict: {}",
                counts,
                result.strictFindings().stream()
                        .limit(MAX_LOGGED_FINDINGS)
                        .map(finding -> finding.kind() + " " + finding.subject())
                        .toList());
        if (log.isDebugEnabled()) {
            result.findings()
                    .forEach(finding -> log.debug("Scope graph finding {} {}", finding.kind(), finding.subject()));
        }
    }

    private static Supplier<ScopeGraphBuildResult> buildFrom(ScopeGraphSourceLoader sourceLoader, Clock clock) {
        ScopeGraphBuilder builder = new ScopeGraphBuilder(clock);
        return () -> builder.build(sourceLoader.load());
    }

    /** One daemon thread: builds are rare, never concurrent, and must not hold the JVM open. */
    private static Executor newExecutor() {
        return new OwnedExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** Marks the executor this bean created, so {@link #shutdown()} stops only its own. */
    private record OwnedExecutor(@NonNull ExecutorService delegate) implements Executor {
        @Override
        public void execute(@NonNull Runnable command) {
            delegate.execute(command);
        }
    }
}
