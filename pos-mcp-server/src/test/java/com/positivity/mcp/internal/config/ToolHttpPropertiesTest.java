package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * {@link ToolHttpProperties} defaults and binding (#1660). Mirrors
 * {@link McpServerPropertiesDefaultsTest}'s real-{@code application.yml} loading style rather than
 * hand-supplied properties, so a drift between the record's compact-constructor defaults and the
 * yml's {@code ${VAR:default}} forms fails here.
 */
class ToolHttpPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(ToolHttpProperties.class)
    static class Config {}

    @Test
    @DisplayName("compact constructor defaults connect/read timeouts to 2s/30s when unbound")
    void unbound_defaultsToTwoAndThirtySeconds() {
        new ApplicationContextRunner().withUserConfiguration(Config.class).run(ctx -> {
            ToolHttpProperties props = ctx.getBean(ToolHttpProperties.class);
            assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(props.readTimeout()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    @DisplayName("application.yml binds pos.tools.http.connect-timeout/read-timeout to 2s/30s")
    void applicationYml_bindsDefaultDurations() {
        new ApplicationContextRunner()
                .withInitializer(loadYaml("application.yml"))
                .withUserConfiguration(Config.class)
                .run(ctx -> {
                    ToolHttpProperties props = ctx.getBean(ToolHttpProperties.class);
                    assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(props.readTimeout()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    @DisplayName("an explicit property overrides the default")
    void explicitProperty_overridesDefault() {
        new ApplicationContextRunner()
                .withPropertyValues("pos.tools.http.connect-timeout=500ms", "pos.tools.http.read-timeout=45s")
                .withUserConfiguration(Config.class)
                .run(ctx -> {
                    ToolHttpProperties props = ctx.getBean(ToolHttpProperties.class);
                    assertThat(props.connectTimeout()).isEqualTo(Duration.ofMillis(500));
                    assertThat(props.readTimeout()).isEqualTo(Duration.ofSeconds(45));
                });
    }

    private static org.springframework.context.ApplicationContextInitializer<
                    org.springframework.context.ConfigurableApplicationContext>
            loadYaml(String... resourcePaths) {
        return ctx -> {
            YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
            try {
                for (int i = 0; i < resourcePaths.length; i++) {
                    java.util.List<PropertySource<?>> sources =
                            loader.load("application-" + i, new ClassPathResource(resourcePaths[i]));
                    for (PropertySource<?> source : sources) {
                        ctx.getEnvironment().getPropertySources().addLast(source);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}
