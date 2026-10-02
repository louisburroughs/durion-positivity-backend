package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.domain.TagName;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The real {@code application.yml} binds {@code mcp.tagging} with one environment name per threshold
 * (ADR-0068 section 6: the bake-off sets them by configuration). Pins that every tag has a threshold
 * entry, that the dotted {@code workflow_state.non-idle} key survives YAML and binding, and that the
 * {@code MCP_TAGGING_*} overrides land on the right keys.
 */
class TaggingPropertiesYamlBindingTest {

    @Test
    @DisplayName("every tag has a threshold entry in application.yml, defaulting to 0.75")
    void defaultsCoverEveryTag() {
        TaggingProperties properties = bind(Map.of());
        for (TagName tag : TagName.values()) {
            assertThat(properties.thresholds())
                    .as("threshold entry for %s", tag.wireName())
                    .containsEntry(tag.wireName(), 0.75);
        }
        assertThat(properties.thresholds()).containsEntry(TaggingProperties.NON_IDLE_THRESHOLD_KEY, 0.75);
        assertThat(properties.nonIdleThreshold()).isEqualTo(0.75);
        assertThat(properties.enforcedTags()).isEmpty();
    }

    @Test
    @DisplayName("MCP_TAGGING_THRESHOLD_* and MCP_TAGGING_ENFORCED_TAGS override the yml values")
    void environmentOverridesLand() {
        TaggingProperties properties = bind(Map.of(
                "MCP_TAGGING_MODE", "enforce",
                "MCP_TAGGING_ENFORCED_TAGS", "simple_chat:veto,workflow_state,intent",
                "MCP_TAGGING_THRESHOLD_SIMPLE_CHAT", "0.80",
                "MCP_TAGGING_THRESHOLD_WORKFLOW_STATE", "0.70",
                "MCP_TAGGING_THRESHOLD_WORKFLOW_STATE_NON_IDLE", "0.80",
                "MCP_TAGGING_THRESHOLD_INTENT", "0.50",
                "MCP_TAGGING_THRESHOLD_ENTITY", "0.85"));
        assertThat(properties.thresholdFor(TagName.SIMPLE_CHAT)).isEqualTo(0.80);
        assertThat(properties.thresholdFor(TagName.WORKFLOW_STATE)).isEqualTo(0.70);
        assertThat(properties.nonIdleThreshold()).isEqualTo(0.80);
        assertThat(properties.thresholdFor(TagName.INTENT)).isEqualTo(0.50);
        assertThat(properties.thresholdFor("entity_workorder")).isEqualTo(0.85);
        assertThat(properties.thresholdFor(TagName.RISK)).isEqualTo(0.75);
        assertThat(properties.enforcedTags()).containsExactly("simple_chat:veto", "workflow_state", "intent");
    }

    private static TaggingProperties bind(Map<String, Object> environmentOverrides) {
        StandardEnvironment environment = new StandardEnvironment();
        // The tests pin what the repo ships plus the given overrides: an exported MCP_TAGGING_* on the
        // developer's or CI's machine must not take part, so the ambient sources are removed.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        try {
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load("tagging-application.yml", new ClassPathResource("application.yml"))) {
                environment.getPropertySources().addLast(source);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        // Stands in for the container environment: the ${MCP_TAGGING_*:default} placeholders resolve
        // against it before binding.
        environment.getPropertySources().addFirst(new MapPropertySource("env-overrides", environmentOverrides));
        return Binder.get(environment)
                .bind("mcp.tagging", TaggingProperties.class)
                .orElseThrow(() -> new AssertionError("mcp.tagging did not bind"));
    }
}
