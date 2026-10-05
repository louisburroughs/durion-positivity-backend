package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@DisplayName("OutboxReplayRequests — a replay request is acknowledged or fails loudly (#2419)")
class OutboxReplayRequestsTest {

    private static final Instant WINDOW_START = Instant.parse("2026-07-14T10:00:00Z");
    private static final ProducerRecord<String, String> REQUEST =
            new ProducerRecord<>("customer.commands.v1", WINDOW_START.toString(), "{}");

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    @SuppressWarnings("unchecked")
    private final CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);

    @AfterEach
    void clearInterruptFlag() {
        // Never leak an interrupt into the next test on this thread.
        Thread.interrupted();
    }

    @Test
    @DisplayName("waits for the broker's acknowledgement within the send timeout")
    void waitsForTheAcknowledgement() throws Exception {
        when(kafkaTemplate.send(REQUEST)).thenReturn(future);

        assertThatCode(() -> OutboxReplayRequests.send(kafkaTemplate, REQUEST, WINDOW_START))
                .doesNotThrowAnyException();

        verify(future).get(eq(OutboxReplayRequests.SEND_TIMEOUT.toMillis()), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("a broker rejection becomes a KafkaException carrying the broker's cause")
    void rejectionPropagates() throws Exception {
        IllegalStateException brokerFailure = new IllegalStateException("not leader");
        when(kafkaTemplate.send(REQUEST)).thenReturn(future);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new ExecutionException(brokerFailure));

        assertThatThrownBy(() -> OutboxReplayRequests.send(kafkaTemplate, REQUEST, WINDOW_START))
                .isInstanceOf(KafkaException.class)
                .hasMessageContaining("customer.commands.v1")
                .hasMessageContaining(WINDOW_START.toString())
                .hasMessageContaining("was rejected")
                .cause()
                .isSameAs(brokerFailure);
    }

    @Test
    @DisplayName("no acknowledgement within the send timeout becomes a KafkaException")
    void timeoutPropagates() throws Exception {
        TimeoutException timeout = new TimeoutException("no ack");
        when(kafkaTemplate.send(REQUEST)).thenReturn(future);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(timeout);

        assertThatThrownBy(() -> OutboxReplayRequests.send(kafkaTemplate, REQUEST, WINDOW_START))
                .isInstanceOf(KafkaException.class)
                .hasMessageContaining("customer.commands.v1")
                .hasMessageContaining(WINDOW_START.toString())
                .hasMessageContaining("was not acknowledged in " + OutboxReplayRequests.SEND_TIMEOUT)
                .cause()
                .isSameAs(timeout);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("an interrupted wait becomes a KafkaException and restores the interrupt flag")
    void interruptPropagatesAndRestoresTheFlag() throws Exception {
        InterruptedException interrupted = new InterruptedException("shutdown");
        when(kafkaTemplate.send(REQUEST)).thenReturn(future);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(interrupted);

        assertThatThrownBy(() -> OutboxReplayRequests.send(kafkaTemplate, REQUEST, WINDOW_START))
                .isInstanceOf(KafkaException.class)
                .hasMessageContaining("customer.commands.v1")
                .hasMessageContaining(WINDOW_START.toString())
                .hasMessageContaining("was interrupted")
                .cause()
                .isSameAs(interrupted);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }
}
