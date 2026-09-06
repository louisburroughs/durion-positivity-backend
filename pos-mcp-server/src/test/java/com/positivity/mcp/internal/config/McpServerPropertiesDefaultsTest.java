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
import org.springframework.core.env.StandardEnvironment;
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
                        // W1.4 (analytics-capability-plan.md §3): widened from 8 so analytical
                        // intents stop starving multi-domain questions of candidate tools. #1840:
                        // widened again past the facade count (18; 17 gated in for alpha's admin);
                        // at 16 the ranking cut dropped one facade per turn, once the date-window
                        // resolver.
                        .isEqualTo("24"));
    }

    @Test
    @DisplayName("application-alpha.yml keeps the discovered-operation cap where it was (#1840)")
    void applicationAlphaYml_keepsTheDiscoveredToolLimit() {
        // The discovered-OpenAPI cap shared candidate-tool-limit before #1840; splitting it keeps
        // the facade widening from adding discovered schemas to every prompt.
        new ApplicationContextRunner()
                .withInitializer(loadYaml("application.yml", "application-alpha.yml"))
                .run(ctx -> assertThat(ctx.getEnvironment().getProperty("mcp.agent.discovered-tool-limit"))
                        .isEqualTo("16"));
    }

    @Test
    @DisplayName("application-alpha.yml's candidate limit stays at or above the facade count (#1840)")
    void applicationAlphaYml_candidateLimitCoversEveryFacade() {
        // #1840's acceptance criterion: the next facade added must not silently reintroduce the
        // random cut. Facades are the @Component *FacadeTool beans in the tools package.
        var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(
                org.springframework.stereotype.Component.class));
        long facadeCount = scanner.findCandidateComponents("com.positivity.mcp.internal.orchestration.tools").stream()
                .map(definition -> definition.getBeanClassName())
                .filter(name -> name != null && name.endsWith("FacadeTool"))
                .count();
        assertThat(facadeCount).as("facade tools found by scan").isGreaterThanOrEqualTo(17);
        new ApplicationContextRunner()
                .withInitializer(loadYaml("application.yml", "application-alpha.yml"))
                .run(ctx -> assertThat(
                                Integer.parseInt(ctx.getEnvironment().getProperty("mcp.agent.candidate-tool-limit")))
                        .as("alpha candidate-tool-limit vs %d facades", facadeCount)
                        .isGreaterThanOrEqualTo((int) facadeCount));
    }

    @Test
    @DisplayName("every profile sends an explicit num_ctx rather than inheriting the Ollama host default")
    void allProfiles_setAnExplicitContextWindow() {
        // #1683: the failure mode this guards is silent — Ollama drops the FRONT of the context
        // (the system prompt) at the host's OLLAMA_CONTEXT_LENGTH, with no error and no log.
        for (String profile : List.of("application.yml", "application-dev.yml", "application-alpha.yml")) {
            new ApplicationContextRunner()
                    .withInitializer(loadYamlWithoutAmbientEnvironment(profile))
                    .run(ctx -> assertThat(ctx.getEnvironment().getProperty("spring.ai.ollama.chat.options.num-ctx"))
                            .as("num-ctx in %s", profile)
                            .isEqualTo("32768"));
        }
    }

    @Test
    @DisplayName("every profile ships deepseek-v4-flash:0731 as the default executor (#1691)")
    void allProfiles_shipTheChosenDefaultExecutor() {
        // #1691: chosen over gpt-oss:120b on the analytics gate. The default is spelled out in each
        // profile and again as the @Value fallback in the Ollama configuration, so one missed site
        // would leave a profile on the old executor with nothing else failing.
        for (String profile : List.of("application.yml", "application-dev.yml", "application-alpha.yml")) {
            new ApplicationContextRunner()
                    .withInitializer(loadYamlWithoutAmbientEnvironment(profile))
                    .run(ctx -> assertThat(ctx.getEnvironment().getProperty("spring.ai.ollama.chat.options.model"))
                            .as("executor model in %s", profile)
                            .isEqualTo("deepseek-v4-flash:0731"));
        }
    }

    @Test
    @DisplayName("every profile runs the executor at temperature 0 so gate runs are reproducible")
    void allProfiles_runTheExecutorDeterministically() {
        for (String profile : List.of("application.yml", "application-dev.yml", "application-alpha.yml")) {
            new ApplicationContextRunner()
                    .withInitializer(loadYamlWithoutAmbientEnvironment(profile))
                    .run(ctx -> assertThat(
                                    ctx.getEnvironment().getProperty("spring.ai.ollama.chat.options.temperature"))
                            .as("temperature in %s", profile)
                            .isEqualTo("0.0"));
        }
    }

    @Test
    @DisplayName("tier routing stays dormant while both tier models resolve to the default executor")
    void tierRouting_isDormantWhileNoTierModelIsConfigured() {
        // #1683: with mcp.model.simple and mcp.model.complex blank, T2-simple and T2-complex are
        // the same model, so a classification call per turn buys a decision with one outcome.
        // Re-enabling tiering is only worth it once mcp.model.simple names a real smaller model.
        new ApplicationContextRunner()
                .withInitializer(loadYamlWithoutAmbientEnvironment("application.yml"))
                .run(ctx -> {
                    assertThat(ctx.getEnvironment().getProperty("mcp.model.tiering-enabled"))
                            .isEqualTo("false");
                    assertThat(ctx.getEnvironment().getProperty("mcp.model.simple"))
                            .isEmpty();
                    assertThat(ctx.getEnvironment().getProperty("mcp.model.complex"))
                            .isEmpty();
                });
    }

    /**
     * Like {@link #loadYaml}, but strips the ambient {@code systemEnvironment} and
     * {@code systemProperties} sources first.
     *
     * <p>Required for the #1683 assertions, which check a SHIPPED DEFAULT. Those YAML values are
     * themselves placeholders ({@code ${OLLAMA_NUM_CTX:32768}} and friends) resolved through the
     * same {@code Environment}, and {@code addLast} leaves {@code systemEnvironment} outranking the
     * YAML — so with the plain loader an exported {@code OLLAMA_CHAT_TEMPERATURE=0.2} (this
     * change's own documented rollback) or {@code MCP_MODEL_SIMPLE=gpt-oss:20b} (its own documented
     * revival step) turned the suite red on a developer's machine while the config was perfectly
     * correct. An operator override is legitimate; what these tests pin is what the repo ships when
     * nobody overrides anything, so the ambient environment must not participate.
     */
    private static org.springframework.context.ApplicationContextInitializer<
                    org.springframework.context.ConfigurableApplicationContext>
            loadYamlWithoutAmbientEnvironment(String... resourcePaths) {
        org.springframework.context.ApplicationContextInitializer<
                        org.springframework.context.ConfigurableApplicationContext>
                delegate = loadYaml(resourcePaths);
        return ctx -> {
            org.springframework.core.env.MutablePropertySources sources =
                    ctx.getEnvironment().getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            delegate.initialize(ctx);
        };
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
