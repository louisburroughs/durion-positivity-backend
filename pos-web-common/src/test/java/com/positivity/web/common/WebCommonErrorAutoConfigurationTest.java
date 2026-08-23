package com.positivity.web.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class WebCommonErrorAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebCommonErrorAutoConfiguration.class));

    @Test
    void registersTheAdviceForServletWebApplications() {
        runner.run(context -> assertThat(context).hasSingleBean(GlobalApiExceptionHandler.class));
    }

    @Test
    void backsOffWhenDisabledByProperty() {
        runner.withPropertyValues("pos.web.global-exception-handler.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(GlobalApiExceptionHandler.class));
    }

    @Test
    void backsOffWhenTheServiceDefinesItsOwnBean() {
        runner.withUserConfiguration(CustomHandlerConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(GlobalApiExceptionHandler.class);
            assertThat(context.getBean(GlobalApiExceptionHandler.class))
                    .isSameAs(context.getBean(CustomHandlerConfiguration.class).handler);
        });
    }

    @Test
    void doesNotRegisterOutsideServletWebApplications() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebCommonErrorAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(GlobalApiExceptionHandler.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomHandlerConfiguration {

        final GlobalApiExceptionHandler handler =
                new GlobalApiExceptionHandler(Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC));

        @Bean
        GlobalApiExceptionHandler customGlobalApiExceptionHandler() {
            return handler;
        }
    }
}
