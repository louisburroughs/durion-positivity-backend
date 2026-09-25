package com.positivity.accounting.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link KafkaEnabledGuard} (#2192; SPEC-inventory-adjustment-gl-posting.md §5.4, D3): binds through
 * the module's real {@code application.yml} / {@code application-<profile>.yml}, via {@link
 * ConfigDataApplicationContextInitializer}, so what is asserted is the configuration that ships, not
 * a test-local copy of it.
 *
 * <p>The "however it got there" cases set the dotted property key directly rather than the
 * {@code POS_ACCOUNTING_KAFKA_ENABLED} environment variable: {@link ApplicationContextRunner}'s
 * inline {@code withPropertyValues} is a plain, exact-match property source, not a
 * {@code SystemEnvironmentPropertySource}, so it does not reproduce the SCREAMING_SNAKE_CASE
 * relaxed-binding mapping a real OS environment variable gets. The guard reads the property back
 * through {@link org.springframework.core.env.Environment#getProperty}, which is exactly what a
 * real environment-variable override resolves through in production; setting the dotted key here
 * exercises that same read path without depending on Spring's own (separately tested) env-var
 * name-mapping.
 */
@DisplayName("KafkaEnabledGuard — fail loud when pos.accounting.kafka.enabled is off (#2192)")
class KafkaEnabledGuardTest {

    private ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(KafkaEnabledGuard.class)
                .withPropertyValues(properties);
    }

    @Test
    @DisplayName("prod + pos.accounting.kafka.enabled resolved false fails startup naming the property")
    void prodWithFlagResolvedFalseFailsStartup() {
        runner("spring.profiles.active=prod", "pos.accounting.kafka.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertFailureNamesProperty(context);
                });
    }

    @Test
    @DisplayName("alpha + pos.accounting.kafka.enabled resolved false fails startup naming the property")
    void alphaWithFlagResolvedFalseFailsStartup() {
        runner("spring.profiles.active=alpha", "pos.accounting.kafka.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertFailureNamesProperty(context);
                });
    }

    @Test
    @DisplayName("dev + pos.accounting.kafka.enabled=false loads: dev is exempt by design")
    void devWithFlagFalseLoads() {
        runner("spring.profiles.active=dev", "pos.accounting.kafka.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(KafkaEnabledGuard.class)).isEmpty();
                });
    }

    @Test
    @DisplayName("prod with no override resolves the property true from application-prod.yml")
    void prodWithNoOverrideResolvesTrue() {
        runner("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty(KafkaEnabledGuard.PROPERTY, Boolean.class))
                    .isTrue();
        });
    }

    @Test
    @DisplayName("alpha with no override resolves the property true from application-alpha.yml")
    void alphaWithNoOverrideResolvesTrue() {
        runner("spring.profiles.active=alpha").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty(KafkaEnabledGuard.PROPERTY, Boolean.class))
                    .isTrue();
        });
    }

    private static void assertFailureNamesProperty(AssertableApplicationContext context) {
        assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining(KafkaEnabledGuard.PROPERTY);
    }
}
