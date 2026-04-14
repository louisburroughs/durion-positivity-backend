package com.positivity.accounting.internal.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Enables Kafka listener infrastructure for pos-accounting when Kafka
 * integration
 * is enabled.
 *
 * <p>
 * Activation is controlled by {@code pos.accounting.kafka.enabled=true} in
 * application configuration. When disabled (the default), no Kafka beans are
 * registered and the module runs without a message broker dependency.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class KafkaConfig {}
