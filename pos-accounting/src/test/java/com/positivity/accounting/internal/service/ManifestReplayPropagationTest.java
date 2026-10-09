package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Each reconciliation-manifest listener in this module lets a failed replay request reach the
 * container's error handler (#2452): the owner publishes a window's manifest once, so a swallowed
 * failure would leave that window's drift unrepaired for good. An unparseable manifest stays
 * dropped. The helper's own timeout and interrupt handling is in {@code OutboxReplayRequestsTest}.
 */
@DisplayName("Manifest listeners propagate a failed replay request (#2452)")
class ManifestReplayPropagationTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000002");
    private static final Instant START = Instant.parse("2026-10-03T12:00:00Z");
    private static final Instant END = Instant.parse("2026-10-03T12:05:00Z");

    /** A manifest that claims three events; the mocked ledger holds none, so every window drifts. */
    private static final String DRIFTED_MANIFEST = """
        {"eventId":"evt-1","eventType":"x.reconciliation.manifest",
         "payload":{"tenantId":"%s","windowStartUtc":"%s","windowEndUtc":"%s","eventCount":3,
           "eventIdsChecksum":"owner-checksum","eventTypeCounts":null}}
        """.formatted(TENANT, START, END);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    static Stream<Class<?>> listeners() {
        return Stream.of(new Class<?>[] {
            InvoiceManifestListener.class, OrderManifestListener.class, PeopleContactManifestListener.class
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listeners")
    @DisplayName("a replay request that fails to send propagates")
    void failedSendPropagates(Class<?> type) throws Exception {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("broker down"));

        assertThatThrownBy(() -> onManifest(type, DRIFTED_MANIFEST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broker down");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listeners")
    @DisplayName("a replay request the broker rejects propagates")
    void brokerRejectionPropagates(Class<?> type) throws Exception {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenAnswer(invocation -> CompletableFuture.failedFuture(new KafkaException("not leader")));

        assertThatThrownBy(() -> onManifest(type, DRIFTED_MANIFEST))
                .isInstanceOf(KafkaException.class)
                .hasRootCauseMessage("not leader");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listeners")
    @DisplayName("an acknowledged replay request completes normally")
    void acknowledgedRequestCompletes(Class<?> type) throws Exception {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        assertThatCode(() -> onManifest(type, DRIFTED_MANIFEST)).doesNotThrowAnyException();

        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listeners")
    @DisplayName("an unparseable manifest is still dropped without a replay request")
    void unparseableManifestStaysDropped(Class<?> type) throws Exception {
        assertThatCode(() -> onManifest(type, "{not json")).doesNotThrowAnyException();

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    private void onManifest(Class<?> type, String message) throws Exception {
        Object listener = newListener(type);
        try {
            type.getMethod("onManifest", String.class).invoke(listener, message);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** Builds the listener from mocks (a real mapper), then fills its {@code @Value} topic fields. */
    private Object newListener(Class<?> type) throws Exception {
        Constructor<?> constructor = type.getConstructors()[0];
        Object[] args = Arrays.stream(constructor.getParameterTypes())
                .map(this::argumentOf)
                .toArray();
        Object listener = constructor.newInstance(args);
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class
                    && field.isAnnotationPresent(Value.class)) {
                ReflectionTestUtils.setField(listener, field.getName(), "owner.commands.v1");
            }
        }
        return listener;
    }

    private Object argumentOf(Class<?> parameter) {
        if (parameter == KafkaTemplate.class) {
            return kafkaTemplate;
        }
        if (parameter == ObjectMapper.class) {
            return new ObjectMapper();
        }
        if (parameter == ObjectProvider.class) {
            return mock(ObjectProvider.class);
        }
        return mock(parameter);
    }
}
