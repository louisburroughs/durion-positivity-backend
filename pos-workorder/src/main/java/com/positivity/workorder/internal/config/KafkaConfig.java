package com.positivity.workorder.internal.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Enables Kafka listener infrastructure for pos-workorder when Kafka
 * integration
 * is enabled.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class KafkaConfig {
}
