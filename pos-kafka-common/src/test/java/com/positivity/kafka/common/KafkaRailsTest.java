package com.positivity.kafka.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * {@link KafkaRails} (#2195, ADR-0044 §4 tier-1 flip): the Kafka rails are present in every deployed
 * profile with no enable flag, and absent only in the broker-less {@code dev} and test profiles.
 */
@DisplayName("KafkaRails — always on outside the broker-less dev/test profiles (#2195)")
class KafkaRailsTest {

    @KafkaRails
    @Configuration(proxyBeanMethods = false)
    static class RailBean {}

    private static ApplicationContextRunner runner(String profiles) {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(RailBean.class);
        return profiles.isEmpty() ? runner : runner.withPropertyValues("spring.profiles.active=" + profiles);
    }

    @ParameterizedTest(name = "profiles=[{0}] -> rails present")
    @ValueSource(strings = {"", "docker", "alpha", "prod", "indus", "dev,local-kafka"})
    void presentInDeployedProfiles(String profiles) {
        runner(profiles).run(context -> assertThat(context).hasSingleBean(RailBean.class));
    }

    @ParameterizedTest(name = "profiles=[{0}] -> rails absent")
    @ValueSource(strings = {"dev", "test", "pg"})
    void absentInBrokerlessProfiles(String profiles) {
        runner(profiles).run(context -> assertThat(context).doesNotHaveBean(RailBean.class));
    }

    @ParameterizedTest(name = "profiles=[{0}] -> no flag can turn the rails off")
    @ValueSource(strings = {"alpha", "prod"})
    void retiredFlagHasNoEffect(String profiles) {
        runner(profiles)
                .withPropertyValues("pos.accounting.kafka.enabled=false")
                .run(context -> assertThat(context).hasSingleBean(RailBean.class));
    }
}
