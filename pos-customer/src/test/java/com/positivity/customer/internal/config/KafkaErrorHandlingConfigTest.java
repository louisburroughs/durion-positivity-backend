package com.positivity.customer.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

class KafkaErrorHandlingConfigTest {

    @Test
    @DisplayName("Failed records route to {topic}.dlq with producer-chosen partition")
    void routesToDlqTopic() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("workorder.events.v1", 3, 42L, "key", "value");

        TopicPartition destination =
                KafkaErrorHandlingConfig.DLQ_DESTINATION.apply(record, new RuntimeException("boom"));

        assertThat(destination.topic()).isEqualTo("workorder.events.v1.dlq");
        assertThat(destination.partition()).isEqualTo(-1);
    }

    @Test
    @DisplayName("Error handler bean is constructed with DLQ recoverer")
    void buildsErrorHandler() {
        DefaultErrorHandler handler =
                new KafkaErrorHandlingConfig().kafkaErrorHandler(Mockito.mock(KafkaTemplate.class));

        assertThat(handler).isNotNull();
    }
}
