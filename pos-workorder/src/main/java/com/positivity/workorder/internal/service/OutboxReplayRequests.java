package com.positivity.workorder.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jspecify.annotations.NonNull;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Sends a manifest listener's {@code {owner}.outbox.replay-requested} command and waits for the
 * broker to acknowledge it (#2419).
 *
 * <p>The owners publish one manifest per consecutive, non-overlapping window, once, so no later
 * manifest re-detects a window whose replay request was lost. A request that cannot be sent, or that
 * the broker rejects, therefore leaves the listener as an exception: the container's error handler
 * retries the manifest with backoff and dead-letters it to {@code {topic}.dlq} when the retries run
 * out (ADR-0044 §4, {@code KafkaErrorHandlingConfig}). Redelivery is safe: the comparison is a read,
 * and the command is keyed by window start, so a repeat asks the owner for the same idempotent
 * replay.
 */
final class OutboxReplayRequests {

    /**
     * How long to wait for the broker's acknowledgement. Well inside the consumer's poll interval,
     * so a slow broker fails this delivery attempt instead of stalling the partition past it.
     */
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

    private OutboxReplayRequests() {}

    /**
     * Sends {@code request} and blocks until the broker acknowledges it.
     *
     * @throws KafkaException if the broker rejects the request, does not answer within {@link
     *     #SEND_TIMEOUT}, or the wait is interrupted; a failure to hand the record to the producer
     *     propagates as thrown
     */
    static void send(
            @NonNull KafkaTemplate<String, String> kafkaTemplate,
            @NonNull ProducerRecord<String, String> request,
            @NonNull Instant windowStart) {
        try {
            kafkaTemplate.send(request).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new KafkaException(failure(request, windowStart, "was rejected"), e.getCause());
        } catch (TimeoutException e) {
            throw new KafkaException(failure(request, windowStart, "was not acknowledged in " + SEND_TIMEOUT), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaException(failure(request, windowStart, "was interrupted"), e);
        }
    }

    private static String failure(ProducerRecord<String, String> request, Instant windowStart, String what) {
        return "Outbox replay request on " + request.topic() + " for window starting " + windowStart + " " + what;
    }
}
