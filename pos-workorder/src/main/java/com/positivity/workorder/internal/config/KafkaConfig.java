package com.positivity.workorder.internal.config;

import com.positivity.kafka.common.KafkaRails;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Enables Kafka listener infrastructure for pos-workorder (a {@link KafkaRails} bean: absent only in the broker-less dev/test profiles).
 */
@Configuration
@EnableKafka
@KafkaRails
public class KafkaConfig {}
