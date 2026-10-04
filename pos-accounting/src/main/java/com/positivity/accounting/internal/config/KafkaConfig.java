package com.positivity.accounting.internal.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Enables Kafka listener infrastructure for pos-accounting.
 *
 * <p>Kafka is tier-1 infrastructure (ADR-0044 §4): this is a {@link KafkaRails} bean, active in
 * every deployed profile and absent only in the broker-less {@code dev} and test profiles.
 */
@Configuration
@EnableKafka
@KafkaRails
public class KafkaConfig {}
