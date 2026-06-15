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
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class McpServerPropertiesDefaultsTest {

    @Configuration
    @EnableConfigurationProperties(McpServerProperties.class)
    static class Config {}

    @Test
    @DisplayName("excluded-path-fragments defaults in application.yml include /admin/, /actuator/, /internal/")
    void excludedPathFragments_defaultToAdminActuatorInternal() {
        new ApplicationContextRunner()
                .withInitializer(loadYaml("application.yml"))
                .withUserConfiguration(Config.class)
                .run(ctx -> {
                    McpServerProperties props = ctx.getBean(McpServerProperties.class);
                    assertThat(props.excludedPathFragments())
                            .containsExactlyInAnyOrder("/admin/", "/actuator/", "/internal/");
                });
    }

    @Test
    @DisplayName("application.yml raises Tomcat request header limit for gateway-expanded auth headers")
    void applicationYml_setsMaxHttpRequestHeaderSize() {
        new ApplicationContextRunner()
                .withInitializer(loadYaml("application.yml"))
                .run(ctx -> assertThat(ctx.getEnvironment().getProperty("server.max-http-request-header-size"))
                        .isEqualTo("32KB"));
    }

    @Test
    @DisplayName("application-alpha.yml points aggregate OpenAPI discovery at the gateway's internal port")
    void applicationAlphaYml_pointsAggregateDiscoveryAtGatewayInternalPort() {
        new ApplicationContextRunner()
                .withInitializer(loadYaml("application-alpha.yml"))
                .withUserConfiguration(Config.class)
                .run(ctx -> {
                    McpServerProperties props = ctx.getBean(McpServerProperties.class);
                    assertThat(props.aggregateSpecUrl()).isEqualTo("http://pos-api-gateway:8080/v3/api-docs");
                });
    }

    @Test
    @DisplayName("application and alpha profiles do not retain the legacy aggregate allowlist prefix")
    void applicationAndAlphaProfiles_doNotRetainLegacyAggregateAllowlistPrefix() {
        new ApplicationContextRunner()
                .withInitializer(loadYaml("application.yml", "application-alpha.yml"))
                .withUserConfiguration(Config.class)
                .run(ctx -> {
                    McpServerProperties props = ctx.getBean(McpServerProperties.class);
                    assertThat(props.includedPathPrefixes()).isEmpty();
                });
    }

    @Test
    @DisplayName("application-alpha.yml increases candidate tool limit for role agents")
    void applicationAlphaYml_increasesCandidateToolLimit() {
        new ApplicationContextRunner()
                .withInitializer(loadYaml("application.yml", "application-alpha.yml"))
                .run(ctx -> assertThat(ctx.getEnvironment().getProperty("mcp.agent.candidate-tool-limit"))
                        .isEqualTo("8"));
    }

    private static org.springframework.context.ApplicationContextInitializer<
                    org.springframework.context.ConfigurableApplicationContext>
            loadYaml(String... resourcePaths) {
        return ctx -> {
            YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
            try {
                for (int i = 0; i < resourcePaths.length; i++) {
                    List<PropertySource<?>> sources =
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
