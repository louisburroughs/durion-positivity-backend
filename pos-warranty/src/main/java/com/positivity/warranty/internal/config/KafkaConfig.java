package com.positivity.warranty.internal.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Enables Kafka listener infrastructure for pos-warranty when Kafka integration is enabled
 * (pos-accounting {@code KafkaConfig} pattern).
 *
 * <p>Activation is controlled by {@code pos.warranty.kafka.enabled=true} in application
 * configuration. When disabled (the default), no listener containers are registered and the
 * module runs without a message broker dependency — the outbox writer/publisher share the
 * same flag.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "pos.warranty.kafka", name = "enabled", havingValue = "true")
public class KafkaConfig {}
