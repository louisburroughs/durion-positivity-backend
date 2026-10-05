package com.positivity.kafka.common;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Registers {@link KafkaRailsProfileGuard} in every module that depends on pos-kafka-common. */
@AutoConfiguration
public class KafkaRailsAutoConfiguration {

    @Bean
    static KafkaRailsProfileGuard kafkaRailsProfileGuard(Environment environment) {
        return new KafkaRailsProfileGuard(environment);
    }
}
