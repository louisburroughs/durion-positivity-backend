package com.positivity.mcp.internal.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Enables Kafka listener infrastructure for pos-mcp-server (#1613).
 *
 * <p>Activated by {@code pos.mcp.kafka.enabled=true}. When disabled — the default — no Kafka beans
 * are registered and the service runs without a broker, falling back to the startup pull, the
 * on-miss fetch, and the scheduled re-pull. The broker is an optimization here, not a dependency:
 * it removes the staleness window on a persona edit, and nothing else.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "pos.mcp.kafka", name = "enabled", havingValue = "true")
public class KafkaConfig {}
