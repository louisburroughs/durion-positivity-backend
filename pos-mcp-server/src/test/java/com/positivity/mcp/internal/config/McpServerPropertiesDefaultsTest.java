package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;

class McpServerPropertiesDefaultsTest {

    @Configuration
    @EnableConfigurationProperties(McpServerProperties.class)
    static class Config {}

    @Test
    @DisplayName("excluded-path-fragments defaults in application.yml include /admin/, /actuator/, /internal/")
    void excludedPathFragments_defaultToAdminActuatorInternal() {
        new ApplicationContextRunner()
                .withInitializer(ctx -> {
                    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
                    try {
                        List<PropertySource<?>> sources =
                                loader.load("application", new ClassPathResource("application.yml"));
                        for (PropertySource<?> source : sources) {
                            ctx.getEnvironment().getPropertySources().addLast(source);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .withUserConfiguration(Config.class)
                .run(ctx -> {
                    McpServerProperties props = ctx.getBean(McpServerProperties.class);
                    assertThat(props.excludedPathFragments())
                            .containsExactlyInAnyOrder("/admin/", "/actuator/", "/internal/");
                });
    }
}
