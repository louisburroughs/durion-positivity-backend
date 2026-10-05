package com.positivity.invoice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;

class KafkaErrorHandlingConfigTest {

    @Test
    @DisplayName("Failed records route to {topic}.dlq with producer-chosen partition")
    void routesToDlqTopic() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("customer.events.v1", 3, 42L, "key", "value");

        TopicPartition destination =
                KafkaErrorHandlingConfig.DLQ_DESTINATION.apply(record, new RuntimeException("boom"));

        assertThat(destination.topic()).isEqualTo("customer.events.v1.dlq");
        assertThat(destination.partition()).isEqualTo(-1);
    }

    @Test
    @DisplayName("A listener exception is retried, then the record is published to {topic}.dlq")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void retriesThenDeadLetters() {
        KafkaTemplate template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        DefaultErrorHandler handler = new KafkaErrorHandlingConfig().kafkaErrorHandler(template);
        // Same recoverer and classification, but no wall-clock backoff so the test runs instantly.
        handler.setBackOffFunction((rec, ex) -> new FixedBackOff(0L, 5L));
        ConsumerRecord<String, String> record = new ConsumerRecord<>("workorder.events.v1", 0, 7L, "k", "v");
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        RuntimeException failure = new IllegalStateException("handler failed");

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThatThrownBy(() -> handler.handleRemaining(failure, List.of(record), consumer, container))
                    .hasMessageContaining("Record in retry");
            verify(template, never()).send(any(ProducerRecord.class));
        }
        handler.handleRemaining(failure, List.of(record), consumer, container);

        ArgumentCaptor<ProducerRecord> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("workorder.events.v1.dlq");
    }
}
