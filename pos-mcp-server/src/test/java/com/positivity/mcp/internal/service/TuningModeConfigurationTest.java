package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The module's {@code application.yml} must leave {@code mcp.tuning.mode} blank when {@code
 * MCP_TUNING_MODE} is unset: a hard default of {@code off} would reach {@link TuningMode#resolve}
 * before the deprecated {@code MCP_TUNING_ENABLED=true} could mean {@code live}, which the README and
 * the configuration metadata promise it still does.
 */
@DisplayName("mcp.tuning.mode resolution from application.yml")
class TuningModeConfigurationTest {

    @Test
    @DisplayName("neither variable set: off")
    void nothingSet_isOff() throws IOException {
        assertThat(resolve(new MockEnvironment())).isEqualTo(TuningMode.OFF);
    }

    @Test
    @DisplayName("legacy MCP_TUNING_ENABLED=true alone still means live")
    void legacyFlagAlone_isLive() throws IOException {
        assertThat(resolve(new MockEnvironment().withProperty("MCP_TUNING_ENABLED", "true")))
                .isEqualTo(TuningMode.LIVE);
    }

    @Test
    @DisplayName("MCP_TUNING_MODE wins over the legacy flag")
    void modeWinsOverLegacyFlag() throws IOException {
        assertThat(resolve(new MockEnvironment()
                        .withProperty("MCP_TUNING_MODE", "shadow")
                        .withProperty("MCP_TUNING_ENABLED", "true")))
                .isEqualTo(TuningMode.SHADOW);
    }

    private static TuningMode resolve(MockEnvironment environment) throws IOException {
        List<PropertySource<?>> yaml =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        yaml.forEach(source -> environment.getPropertySources().addLast(source));
        return TuningMode.resolve(
                environment.getProperty("mcp.tuning.mode"), environment.getProperty("mcp.tuning.enabled"));
    }
}
