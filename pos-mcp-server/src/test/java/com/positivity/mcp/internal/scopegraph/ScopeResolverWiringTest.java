package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.config.StaticRagPreloadProperties;
import com.positivity.mcp.internal.domain.WorkflowState;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The resolver and its meters as Spring wires them, in each mode. */
class ScopeResolverWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);

    @Test
    @DisplayName("mode off (the default): the beans exist, resolve nothing, register no meter and log nothing")
    void offIsInert() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger logger = (Logger) LoggerFactory.getLogger("com.positivity.mcp.internal.scopegraph");
        logger.addAppender(logs);
        try {
            runner.run(context -> {
                ScopeResolver resolver = context.getBean(ScopeResolver.class);

                assertThat(resolver.enabled()).isFalse();
                assertThat(resolver.resolve("the work order WO-20391", Set.of("AUTHENTICATED"), WorkflowState.IDLE))
                        .isSameAs(ScopeSet.empty());
                context.getBean(ScopeMetrics.class).recordCalledTools(1, 2);
                context.getBean(ScopeMetrics.class).recordRetrievedDocuments(1, 2);
                context.getBean(ScopeMetrics.class).recordError();

                assertThat(context.getBean(MeterRegistry.class).getMeters()).isEmpty();
            });
            assertThat(logs.list).isEmpty();
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    @DisplayName("mode shadow: the scope meters are registered up front, with closed tag sets")
    void shadowRegistersTheScopeMeters() {
        runner.withPropertyValues("mcp.scope-graph.mode=shadow").run(context -> {
            ScopeResolver resolver = context.getBean(ScopeResolver.class);
            assertThat(resolver.enabled()).isTrue();
            // Before the first build the graph is empty: a turn resolves to NONE, not to an error.
            assertThat(resolver.resolve("the work order", Set.of("AUTHENTICATED"), WorkflowState.IDLE)
                            .confidence())
                    .isEqualTo(ScopeSet.Confidence.NONE);

            MeterRegistry meters = context.getBean(MeterRegistry.class);
            assertThat(meters.getMeters().stream()
                            .map(Meter::getId)
                            .map(Meter.Id::getName)
                            .filter(name -> name.startsWith("mcp.scope.")))
                    .containsOnly(
                            "mcp.scope.resolved",
                            "mcp.scope.size",
                            "mcp.scope.called_tool",
                            "mcp.scope.retrieved_doc",
                            "mcp.scope.errors",
                            "mcp.scope.fallback");
            assertThat(meters.get("mcp.scope.resolved")
                            .tag("confidence", "NONE")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
            assertThat(meters.get("mcp.scope.resolved").counters()).hasSize(3);
            assertThat(meters.get("mcp.scope.size").summaries()).hasSize(4);
            assertThat(meters.get("mcp.scope.called_tool").counters()).hasSize(2);
            assertThat(meters.get("mcp.scope.errors").counter().count()).isZero();
            // ADR-0069 §6: one fallback counter per consumer, registered up front, none incremented.
            assertThat(meters.get("mcp.scope.fallback").counters()).hasSize(3);
            assertThat(meters.get("mcp.scope.fallback").counters().stream()
                            .map(counter -> counter.getId().getTag("consumer")))
                    .containsExactlyInAnyOrder("rag", "tools", "card");
        });
    }

    @Test
    @DisplayName("mode enforce resolves exactly as shadow does, and the listed consumers are the ones that act")
    void enforceResolvesLikeShadow() {
        runner.withPropertyValues("mcp.scope-graph.mode=enforce", "mcp.scope-graph.enforce=rag,tools,card")
                .run(context -> {
                    assertThat(context.getBean(ScopeResolver.class).enabled()).isTrue();
                    ScopeConsumers consumers = context.getBean(ScopeConsumers.class);
                    for (ScopeGraphProperties.Consumer consumer : ScopeGraphProperties.Consumer.values()) {
                        assertThat(consumers.enforces(consumer)).isTrue();
                    }
                    assertThat(consumers.addedToolSlots()).isEqualTo(8);
                });
        // ADR-0069 §9 / spec §2.1: enforce with an empty list is shadow; a listed consumer acts alone.
        runner.withPropertyValues("mcp.scope-graph.mode=enforce").run(context -> {
            ScopeConsumers consumers = context.getBean(ScopeConsumers.class);
            for (ScopeGraphProperties.Consumer consumer : ScopeGraphProperties.Consumer.values()) {
                assertThat(consumers.enforces(consumer)).isFalse();
            }
        });
        runner.withPropertyValues("mcp.scope-graph.mode=enforce", "mcp.scope-graph.enforce=rag")
                .run(context -> {
                    ScopeConsumers consumers = context.getBean(ScopeConsumers.class);
                    assertThat(consumers.enforces(ScopeGraphProperties.Consumer.RAG))
                            .isTrue();
                    assertThat(consumers.enforces(ScopeGraphProperties.Consumer.TOOLS))
                            .isFalse();
                    assertThat(consumers.enforces(ScopeGraphProperties.Consumer.CARD))
                            .isFalse();
                });
        runner.withPropertyValues("mcp.scope-graph.mode=shadow", "mcp.scope-graph.enforce=rag,tools,card")
                .run(context -> {
                    ScopeConsumers consumers = context.getBean(ScopeConsumers.class);
                    for (ScopeGraphProperties.Consumer consumer : ScopeGraphProperties.Consumer.values()) {
                        assertThat(consumers.enforces(consumer)).isFalse();
                    }
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ScopeGraphProperties.class, StaticRagPreloadProperties.class})
    @Import({
        ScopeGraphHolder.class,
        ScopeGraphSourceLoader.class,
        OpenApiSchemaIndexHolder.class,
        ScopeMetrics.class,
        ScopeResolver.class,
        ScopeConsumers.class
    })
    static class Wiring {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ScopeGraphCatalogReader catalogReader() {
            return ScopeGraphTestFixtures::catalog;
        }
    }
}
