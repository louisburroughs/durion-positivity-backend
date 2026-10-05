package com.positivity.mcp.internal.config;

import com.positivity.kafka.common.KafkaRails;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Enables Kafka listener infrastructure for pos-mcp-server (#1613).
 *
 * <p>A {@code @KafkaRails} bean (ADR-0044 §4): active in every deployed profile, absent only in the
 * broker-less dev and test profiles, where the service falls back to the startup pull, the
 * on-miss fetch, and the scheduled re-pull. The listener removes the staleness window on a persona
 * edit.
 */
@Configuration
@EnableKafka
@KafkaRails
public class KafkaConfig {}
