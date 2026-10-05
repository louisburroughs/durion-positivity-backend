package com.positivity.platformsender.internal.config;

import com.positivity.kafka.common.KafkaRails;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Enables Kafka listener infrastructure for pos-platform-sender (ADR-0044 §4 tier-1 rails)
 * (pos-accounting {@code KafkaConfig} pattern).
 *
 * <p>A {@code @KafkaRails} bean: active in every deployed profile, absent only in the
 * broker-less dev and test profiles. The outbox writer/publisher share the same gate.
 */
@Configuration
@EnableKafka
@KafkaRails
public class KafkaConfig {}
