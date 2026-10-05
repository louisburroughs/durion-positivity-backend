package com.positivity.kafka.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("KafkaRailsProfileGuard - broker-less plus deployed profile fails startup (#2463)")
class KafkaRailsProfileGuardTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(KafkaRailsAutoConfiguration.class));

    @ParameterizedTest(name = "profiles=[{0}] -> startup fails")
    @ValueSource(strings = {"dev,alpha", "test,prod", "pg,alpha", "dev,alpha,prod"})
    void failsWhenBrokerlessMeetsDeployed(String profiles) {
        runner.withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(context.getStartupFailure().getMessage() + rootMessage(context.getStartupFailure()))
                    .contains("@KafkaRails");
        });
    }

    @ParameterizedTest(name = "profiles=[{0}] -> startup ok")
    @ValueSource(
            strings = {"", "dev", "test", "pg", "alpha", "prod", "docker", "dev,local-kafka", "alpha,dev,local-kafka"})
    void startsOtherwise(String profiles) {
        runner.withPropertyValues("spring.profiles.active=" + profiles)
                .run(context -> assertThat(context).hasSingleBean(KafkaRailsProfileGuard.class));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }
}
