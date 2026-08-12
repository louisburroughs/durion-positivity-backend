package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.invoice.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * pos-invoice runs one reconciliation listener per upstream owner it replicates,
 * and all three are structurally identical, so the shared contract is pinned once
 * here (ADR-0044 §4).
 *
 * <p>
 * Reconciliation is the only thing that notices a lost fact. What makes it safe
 * to run unattended is that the verdict is stateless — the same manifest
 * reprocessed yields the same answer, so a redelivery costs one idempotent
 * replay rather than corruption — and that drift both increments the metric and
 * requests a replay naming the window that failed. Metric without replay is
 * silent divergence; replay without metric means nobody learns it is happening.
 *
 * <p>
 * Each listener must also request its replay on <em>its own</em> owner's commands
 * topic. A misrouted replay would repair nothing and would look like a
 * successful repair attempt in the logs. (The workorder listener's topic field is
 * named {@code locationCommandsTopic} — a copy-paste leftover — but the
 * {@code @Value} key and default bind the workorder topic, and the test below
 * pins the routing rather than the field name.)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("pos-invoice reconciliation manifest listeners — shared drift contract")
class ManifestListenerContractTest {

    private static final Instant WINDOW_START = Instant.parse("2026-08-11T09:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-08-11T09:05:00Z");

    static final List<String> LISTENERS = List.of("customer", "location", "workorder");

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);
    }

    private record Listener(String owner, String commandsTopic, Consumer<String> dispatch) {}

    private Listener listener(String owner) {
        switch (owner) {
            case "customer" -> {
                CustomerManifestListener listener = new CustomerManifestListener(
                        processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
                ReflectionTestUtils.setField(listener, "customerCommandsTopic", "customer.commands.v1");
                return new Listener(CustomerEventsListener.OWNER, "customer.commands.v1", listener::onManifest);
            }
            case "location" -> {
                LocationManifestListener listener = new LocationManifestListener(
                        processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
                ReflectionTestUtils.setField(listener, "locationCommandsTopic", "location.commands.v1");
                return new Listener(LocationEventsListener.OWNER, "location.commands.v1", listener::onManifest);
            }
            default -> {
                WorkorderManifestListener listener = new WorkorderManifestListener(
                        processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
                // Field name is a copy-paste leftover; the @Value key binds the workorder topic.
                ReflectionTestUtils.setField(listener, "locationCommandsTopic", "workorder.commands.v1");
                return new Listener(WorkorderEventsListener.OWNER, "workorder.commands.v1", listener::onManifest);
            }
        }
    }

    private String manifest(long eventCount, String checksum) {
        return """
                {"eventId":"evt-1","eventType":"x.reconciliation.manifest",
                 "payload":{"windowStartUtc":"%s","windowEndUtc":"%s","eventCount":%d,
                   "eventIdsChecksum":"%s","eventTypeCounts":null}}
                """.formatted(WINDOW_START, WINDOW_END, eventCount, checksum);
    }

    private void replicaHolds(Listener listener, List<String> eventIds) {
        when(processedEventRepository.findEventIdsInRange(
                        listener.owner(),
                        UuidV7Timestamps.minStringAt(WINDOW_START),
                        UuidV7Timestamps.minStringAt(WINDOW_END)))
                .thenReturn(eventIds);
    }

    private double driftCount(String owner) {
        return meterRegistry.find("replica.drift").tag("owner", owner).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("stays silent when the local replica matches the owner's manifest")
    void matchingWindowRequestsNothing(String owner) {
        Listener listener = listener(owner);
        List<String> ids = List.of("019ff000-0000-7000-8000-000000000001");
        replicaHolds(listener, ids);

        listener.dispatch().accept(manifest(ids.size(), ReconciliationManifestV1.checksumOf(ids)));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        assertThat(driftCount(listener.owner())).isZero();
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("routes the replay to its own owner's commands topic, keyed on the window start")
    void replayGoesToTheOwnersTopic(String owner) {
        Listener listener = listener(owner);
        replicaHolds(listener, List.of());

        listener.dispatch().accept(manifest(2, "owner-checksum"));

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        // A misrouted replay repairs nothing while looking like a successful attempt.
        verify(kafkaTemplate)
                .send(
                        org.mockito.ArgumentMatchers.eq(listener.commandsTopic()),
                        org.mockito.ArgumentMatchers.eq(WINDOW_START.toString()),
                        command.capture());
        var payload = objectMapper.readTree(command.getValue()).path("payload");
        assertThat(payload.path("since").asString()).isEqualTo(WINDOW_START.toString());
        assertThat(payload.path("until").asString()).isEqualTo(WINDOW_END.toString());
        assertThat(driftCount(listener.owner())).isEqualTo(1.0);
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("treats a matching count with a different checksum as drift")
    void checksumMismatchIsDrift(String owner) {
        Listener listener = listener(owner);
        List<String> ids = List.of("019ff000-0000-7000-8000-000000000001");
        replicaHolds(listener, ids);

        // Same number of events, different identities — a count-only check would have passed.
        listener.dispatch().accept(manifest(ids.size(), "a-different-checksum"));

        assertThat(driftCount(listener.owner())).isEqualTo(1.0);
        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("reaches the same verdict every time the same manifest is reprocessed")
    void verdictIsStateless(String owner) {
        Listener listener = listener(owner);
        replicaHolds(listener, List.of());
        String message = manifest(2, "owner-checksum");

        listener.dispatch().accept(message);
        listener.dispatch().accept(message);

        assertThat(driftCount(listener.owner())).isEqualTo(2.0);
        verify(kafkaTemplate, org.mockito.Mockito.times(2)).send(anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("drops an unparseable manifest rather than retrying it")
    void unparseableManifestIsDropped(String owner) {
        listener(owner).dispatch().accept("{not json");

        verify(processedEventRepository, never()).findEventIdsInRange(anyString(), anyString(), anyString());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("still counts drift when the replay request cannot be published")
    void replayPublishFailureStillCountsDrift(String owner) {
        Listener listener = listener(owner);
        replicaHolds(listener, List.of());
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("broker down"));

        listener.dispatch().accept(manifest(3, "owner-checksum"));

        // Best effort by design: a broker outage must not take the consumer down with it.
        assertThat(driftCount(listener.owner())).isEqualTo(1.0);
    }
}
