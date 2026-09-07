package com.positivity.security.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link LocationScopeAutoConfiguration} registers the denial handler for servlet applications
 * only, and steps aside for a module that defines its own (#1870).
 */
@DisplayName("LocationScopeAutoConfiguration")
class LocationScopeAutoConfigurationTest {

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LocationScopeAutoConfiguration.class));

    @Test
    @DisplayName("a servlet application gets the handler")
    void servletApplicationGetsHandler() {
        webRunner.run(context -> assertThat(context).hasSingleBean(LocationScopeDeniedExceptionHandler.class));
    }

    @Test
    @DisplayName("a module-defined handler bean suppresses the auto-configured one")
    void userBeanBacksOff() {
        webRunner.withUserConfiguration(CustomHandlerConfig.class).run(context -> {
            assertThat(context).hasSingleBean(LocationScopeDeniedExceptionHandler.class);
            assertThat(context.getBean(LocationScopeDeniedExceptionHandler.class))
                    .isSameAs(CustomHandlerConfig.HANDLER);
        });
    }

    @Test
    @DisplayName("a non-web application gets nothing")
    void nonWebApplicationGetsNothing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LocationScopeAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(LocationScopeDeniedExceptionHandler.class));
    }

    @Configuration
    static class CustomHandlerConfig {
        static final LocationScopeDeniedExceptionHandler HANDLER = new LocationScopeDeniedExceptionHandler(
                Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC));

        @Bean
        LocationScopeDeniedExceptionHandler locationScopeDeniedExceptionHandler() {
            return HANDLER;
        }
    }
}
