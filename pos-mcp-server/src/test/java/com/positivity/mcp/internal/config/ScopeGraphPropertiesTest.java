package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.ScopeGraphProperties.Consumer;
import com.positivity.mcp.internal.config.ScopeGraphProperties.Mode;
import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/** ADR-0069 section 9 / spec 2.1: the {@code mcp.scope-graph} keys, bound the way the runtime binds them. */
class ScopeGraphPropertiesTest {

    private static final String PREFIX = "mcp.scope-graph";

    @Test
    @DisplayName("with nothing configured the graph is off and the caps take their defaults")
    void defaults() {
        ScopeGraphProperties properties =
                Binder.get(new StandardEnvironment()).bindOrCreate(PREFIX, ScopeGraphProperties.class);

        assertThat(properties.mode()).isEqualTo(Mode.OFF);
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.enforce()).isEmpty();
        assertThat(properties.maxNodes()).isEqualTo(60);
        assertThat(properties.addedToolSlots()).isEqualTo(8);
        assertThat(properties.cardTokenBudget()).isEqualTo(400);
        assertThat(ScopeGraphProperties.off()).isEqualTo(properties);
    }

    @Test
    @DisplayName("application.yml ships mode off, nothing enforced, and the documented defaults")
    void shippedDefaults() {
        ScopeGraphProperties properties = bind(new ClassPathResource("application.yml"));

        assertThat(properties).isEqualTo(ScopeGraphProperties.off());
    }

    @Test
    @DisplayName("application-test.yml pins mode off over any base value")
    void testProfilePinsOff() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("base", Map.of(PREFIX + ".mode", "enforce")));
        load(new ClassPathResource("application-test.yml"))
                .forEach(source -> environment.getPropertySources().addFirst(source));

        assertThat(Binder.get(environment)
                        .bindOrCreate(PREFIX, ScopeGraphProperties.class)
                        .mode())
                .isEqualTo(Mode.OFF);
    }

    @Test
    @DisplayName("shadow builds and resolves, and enforces nothing")
    void shadow() {
        ScopeGraphProperties properties = bind(yaml("""
                mcp:
                  scope-graph:
                    mode: shadow
                    enforce: rag, tools
                """));

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.enforces(Consumer.RAG)).isFalse();
        assertThat(properties.enforces(Consumer.TOOLS)).isFalse();
    }

    @Test
    @DisplayName("enforce acts only for the listed consumers; an empty list behaves as shadow")
    void enforcePerConsumer() {
        ScopeGraphProperties listed = bind(yaml("""
                mcp:
                  scope-graph:
                    mode: enforce
                    enforce: [rag, card, rag]
                    max-nodes: 40
                    added-tool-slots: 4
                    card-token-budget: 250
                """));

        assertThat(listed.enforce()).containsExactly(Consumer.RAG, Consumer.CARD);
        assertThat(listed.enforces(Consumer.RAG)).isTrue();
        assertThat(listed.enforces(Consumer.CARD)).isTrue();
        assertThat(listed.enforces(Consumer.TOOLS)).isFalse();
        assertThat(listed.maxNodes()).isEqualTo(40);
        assertThat(listed.addedToolSlots()).isEqualTo(4);
        assertThat(listed.cardTokenBudget()).isEqualTo(250);

        ScopeGraphProperties empty = bind(yaml("""
                mcp:
                  scope-graph:
                    mode: enforce
                """));
        assertThat(empty.enabled()).isTrue();
        assertThat(List.of(Consumer.values())).noneMatch(empty::enforces);
    }

    @Test
    @DisplayName("a preload entry binds its entities list, and defaults it to empty")
    void staticDocEntryEntities() {
        StandardEnvironment environment = new StandardEnvironment();
        load(yaml("""
                        mcp:
                          rag:
                            preload:
                              docs:
                                - id: workorder.status-lifecycle
                                  source-path: classpath:rag/x.md
                                  rag-scope: workorder
                                  entities: [workorder, estimate]
                                - id: glossary.identifiers
                                  source-path: classpath:rag/y.md
                                  entities: [none]
                                - id: legacy
                                  source-path: classpath:rag/z.md
                                  required-permissions: ["a:b:c"]
                        """)).forEach(source -> environment.getPropertySources().addFirst(source));

        List<StaticDocEntry> docs = Binder.get(environment)
                .bind("mcp.rag.preload", StaticRagPreloadProperties.class)
                .get()
                .docs();

        assertThat(docs)
                .extracting(StaticDocEntry::entities)
                .containsExactly(List.of("workorder", "estimate"), List.of("none"), List.of());
        assertThat(docs.get(2).requiredPermissions()).containsExactly("a:b:c");
        assertThat(new StaticDocEntry("a", "b", "c").entities()).isEmpty();
        assertThat(new StaticDocEntry("a", "b", "c", List.of("p")).entities()).isEmpty();
    }

    private static ScopeGraphProperties bind(Resource resource) {
        StandardEnvironment environment = new StandardEnvironment();
        load(resource).forEach(source -> environment.getPropertySources().addFirst(source));
        return Binder.get(environment).bindOrCreate(PREFIX, ScopeGraphProperties.class);
    }

    private static List<PropertySource<?>> load(Resource resource) {
        try {
            return new YamlPropertySourceLoader().load("scope-graph-properties-test", resource);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Resource yaml(String text) {
        return new ByteArrayResource(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
