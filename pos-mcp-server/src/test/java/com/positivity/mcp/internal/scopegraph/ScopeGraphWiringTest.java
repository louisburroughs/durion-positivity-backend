package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.config.StaticRagPreloadProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The scope-graph beans wired by Spring, without the rest of the application: the production
 * constructors, the holder's own executor, and the shipped lexicon.
 */
class ScopeGraphWiringTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final AtomicInteger CATALOG_READS = new AtomicInteger();

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);

    @Test
    @DisplayName("mode shadow: a rebuild request builds the graph from the wired sources, off the caller's thread")
    void shadowBuilds() {
        runner.withPropertyValues("mcp.scope-graph.mode=shadow").run(context -> {
            ScopeGraphHolder holder = context.getBean(ScopeGraphHolder.class);
            assertThat(holder.current()).isSameAs(ScopeGraph.empty());

            holder.rebuild();

            ScopeGraph graph = awaitBuilt(holder);
            assertThat(graph.builtAt()).isEqualTo(NOW);
            assertThat(graph.contentHash()).matches("[0-9a-f]{16}");
            // From the shipped lexicon and the fixture catalog.
            assertThat(graph.entityOptions()).contains("workorder", "estimate", "customer");
            assertThat(graph.contains(NodeId.of(NodeType.TOOL, "WorkorderFacadeTool")))
                    .isTrue();
            assertThat(context.getBean(MeterRegistry.class)
                            .get("mcp.scope_graph.nodes")
                            .gauge()
                            .value())
                    .isEqualTo(graph.nodeCount());
        });
    }

    @Test
    @DisplayName("mode off (the default): the beans exist, and a rebuild request reads nothing and builds nothing")
    void offIsInert() {
        runner.run(context -> {
            int readsBefore = CATALOG_READS.get();
            ScopeGraphHolder holder = context.getBean(ScopeGraphHolder.class);

            holder.rebuild();

            assertThat(context.getBean(ScopeGraphProperties.class).enabled()).isFalse();
            assertThat(holder.current()).isSameAs(ScopeGraph.empty());
            assertThat(CATALOG_READS).hasValue(readsBefore);
            assertThat(context.getBean(MeterRegistry.class).getMeters()).isEmpty();
        });
    }

    private static ScopeGraph awaitBuilt(ScopeGraphHolder holder) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (holder.current().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(holder.current().isEmpty())
                .as("the graph was built within 10 s")
                .isFalse();
        return holder.current();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ScopeGraphProperties.class, StaticRagPreloadProperties.class})
    @Import({ScopeGraphHolder.class, ScopeGraphSourceLoader.class, OpenApiSchemaIndexHolder.class})
    static class Wiring {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ScopeGraphCatalogReader catalogReader() {
            return () -> {
                CATALOG_READS.incrementAndGet();
                return ScopeGraphTestFixtures.catalog();
            };
        }
    }
}
