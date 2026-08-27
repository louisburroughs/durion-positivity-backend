package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link PeopleManifestListener} (ADR-0044 §4, #1537).
 *
 * <p>Reconciles the {@code ext_people_staffing_assignment} replica against the owner's
 * {@code people.manifest.v1} summary — the previously-inert consumer half of ADR-0044
 * reconciliation for the staffing feed that drives the mechanic projection: {@code pos-people}
 * was already publishing manifests and handling replay commands, but nothing consumed the
 * former or sent the latter until now.
 */
@DisplayName("PeopleManifestListener — replica reconciliation and replay requests")
class PeopleManifestListenerTest {

    private static final Instant WINDOW_START = Instant.parse("2026-08-11T09:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-08-11T09:05:00Z");
    private static final String COMMANDS_TOPIC = "people.commands.v1";

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SimpleMeterRegistry meterRegistry;
    private PeopleManifestListener listener;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        listener = newListener(meterRegistry);
    }

    @SuppressWarnings("unchecked")
    private PeopleManifestListener newListener(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        PeopleManifestListener created =
                new PeopleManifestListener(processedEvents, kafkaTemplate, objectMapper, provider);
        ReflectionTestUtils.setField(created, "peopleCommandsTopic", COMMANDS_TOPIC);
        return created;
    }

    private String manifestFor(List<String> eventIds) {
        ReconciliationManifestV1 manifest = new ReconciliationManifestV1(
                WINDOW_START, WINDOW_END, eventIds.size(), ReconciliationManifestV1.checksumOf(eventIds), null);
        return "{\"eventType\":\"people.reconciliation.manifest\",\"payload\":"
                + objectMapper.writeValueAsString(manifest) + "}";
    }

    private void replicaHas(List<String> eventIds) {
        when(processedEvents.findEventIdsInRange(anyString(), anyString(), anyString()))
                .thenReturn(eventIds);
    }

    private double driftCount() {
        var counter = meterRegistry.find("replica.drift").tag("owner", "people").counter();
        return counter == null ? 0d : counter.count();
    }

    @Test
    @DisplayName("stays silent when the replica matches the manifest")
    void whenReplicaMatches_requestsNothing() {
        List<String> ids = List.of("id-1", "id-2");
        replicaHas(ids);

        listener.onManifest(manifestFor(ids));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        assertThat(driftCount()).isZero();
    }

    @Test
    @DisplayName("drift publishes exactly one replay command carrying the window bounds")
    void whenDrift_publishesExactlyOneReplayCommandForTheWindow() {
        replicaHas(List.of("id-1"));

        listener.onManifest(manifestFor(List.of("id-1", "id-2")));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, org.mockito.Mockito.times(1)).send(eq(COMMANDS_TOPIC), key.capture(), body.capture());
        assertThat(key.getValue()).isEqualTo(WINDOW_START.toString());

        JsonNode command = objectMapper.readTree(body.getValue());
        assertThat(command.path("commandType").stringValue()).isEqualTo("people.outbox.replay-requested");
        assertThat(command.path("payload").path("since").stringValue()).isEqualTo(WINDOW_START.toString());
        assertThat(command.path("payload").path("until").stringValue()).isEqualTo(WINDOW_END.toString());
        assertThat(driftCount()).isEqualTo(1d);
    }

    @Test
    @DisplayName("scopes the processed-events lookup to the people owner")
    void lookupIsScopedToOwner() {
        replicaHas(List.of());
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);

        listener.onManifest(manifestFor(List.of()));

        verify(processedEvents).findEventIdsInRange(owner.capture(), anyString(), anyString());
        assertThat(owner.getValue()).isEqualTo(PeopleEventsListener.OWNER);
    }

    @Test
    @DisplayName("drops an unparseable manifest without querying or replaying")
    void unparseableManifest_isDropped() {
        listener.onManifest("not a manifest");

        verify(processedEvents, never()).findEventIdsInRange(anyString(), anyString(), anyString());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("swallows a failure to publish the replay request")
    void whenReplayPublishFails_doesNotPropagate() {
        replicaHas(List.of());
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("broker down"));

        listener.onManifest(manifestFor(List.of("id-1")));

        // The drift metric already fired, so the signal is not lost even though the send blew up.
        assertThat(driftCount()).isEqualTo(1d);
    }

    @Test
    @DisplayName("redelivery of the same manifest is idempotent: repeated drift, no exception, same verdict")
    void redeliveryIsIdempotent() {
        replicaHas(List.of("id-1"));
        String message = manifestFor(List.of("id-1", "id-2"));

        listener.onManifest(message);
        listener.onManifest(message);

        // Reprocessing yields the same verdict each time; a duplicate replay is harmless because
        // the owner's replay is deduplicated by the processed_events primary key downstream.
        verify(kafkaTemplate, org.mockito.Mockito.times(2)).send(eq(COMMANDS_TOPIC), anyString(), anyString());
        assertThat(driftCount()).isEqualTo(2d);
    }
}
