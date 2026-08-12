package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.ReconciliationManifestV1;
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
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link PeopleContactManifestListener} (ADR-0044 §4).
 *
 * <p>
 * This is the consumer half of reconciliation: the owner publishes a manifest
 * per window, and this listener recomputes the same summary from its own
 * {@code processed_events} log. Agreement means the replica is intact; anything
 * else means events were lost and a replay is requested over the owner's command
 * topic.
 *
 * <p>
 * The comparison must be exact in both directions. A false negative leaves a
 * replica silently missing data, while a false positive triggers a replay of a
 * whole window for nothing — so both the count and the checksum are asserted,
 * including the case where the count matches but the ids do not.
 */
@DisplayName("PeopleContactManifestListener — replica reconciliation and replay requests")
class PeopleContactManifestListenerTest {

    private static final Instant WINDOW_START = Instant.parse("2026-07-08T11:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-07-08T12:00:00Z");
    private static final String COMMANDS_TOPIC = "people-contact.commands.v1";

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SimpleMeterRegistry meterRegistry;
    private PeopleContactManifestListener listener;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        listener = newListener(meterRegistry);
    }

    @SuppressWarnings("unchecked")
    private PeopleContactManifestListener newListener(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        PeopleContactManifestListener created =
                new PeopleContactManifestListener(processedEvents, kafkaTemplate, objectMapper, provider);
        ReflectionTestUtils.setField(created, "peopleContactCommandsTopic", COMMANDS_TOPIC);
        return created;
    }

    /** Manifest envelope claiming {@code eventIds} were published in the window. */
    private String manifestFor(List<String> eventIds) {
        ReconciliationManifestV1 manifest = new ReconciliationManifestV1(
                WINDOW_START, WINDOW_END, eventIds.size(), ReconciliationManifestV1.checksumOf(eventIds), null);
        return "{\"eventType\":\"people-contact.reconciliation.manifest\",\"payload\":"
                + objectMapper.writeValueAsString(manifest) + "}";
    }

    private void replicaHas(List<String> eventIds) {
        when(processedEvents.findEventIdsInRange(anyString(), anyString(), anyString()))
                .thenReturn(eventIds);
    }

    private double driftCount() {
        var counter = meterRegistry.find("replica.drift").counter();
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
    @DisplayName("requests a replay when the replica is missing events")
    void whenReplicaShort_requestsReplay() {
        replicaHas(List.of("id-1"));

        listener.onManifest(manifestFor(List.of("id-1", "id-2")));

        verify(kafkaTemplate).send(eq(COMMANDS_TOPIC), anyString(), anyString());
        assertThat(driftCount()).isEqualTo(1d);
    }

    @Test
    @DisplayName("requests a replay when the count matches but the event ids do not")
    void whenChecksumDiffersAtSameCount_requestsReplay() {
        replicaHas(List.of("id-1", "id-DIFFERENT"));

        // Count alone would pass here; only the checksum catches a substituted id.
        listener.onManifest(manifestFor(List.of("id-1", "id-2")));

        verify(kafkaTemplate).send(eq(COMMANDS_TOPIC), anyString(), anyString());
        assertThat(driftCount()).isEqualTo(1d);
    }

    @Test
    @DisplayName("requests a replay when the replica holds events the manifest does not list")
    void whenReplicaHasExtra_requestsReplay() {
        replicaHas(List.of("id-1", "id-2", "id-3"));

        listener.onManifest(manifestFor(List.of("id-1", "id-2")));

        verify(kafkaTemplate).send(eq(COMMANDS_TOPIC), anyString(), anyString());
    }

    @Test
    @DisplayName("agrees on an empty window rather than treating zero as drift")
    void whenBothEmpty_requestsNothing() {
        replicaHas(List.of());

        listener.onManifest(manifestFor(List.of()));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("the replay command carries the window bounds and is keyed by window start")
    void replayCommand_carriesWindowBounds() {
        replicaHas(List.of());
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);

        listener.onManifest(manifestFor(List.of("id-1")));

        verify(kafkaTemplate).send(eq(COMMANDS_TOPIC), key.capture(), body.capture());
        assertThat(key.getValue()).isEqualTo(WINDOW_START.toString());

        JsonNode command = objectMapper.readTree(body.getValue());
        assertThat(command.path("commandType").stringValue()).isEqualTo("people-contact.outbox.replay-requested");
        assertThat(command.path("payload").path("since").stringValue()).isEqualTo(WINDOW_START.toString());
        assertThat(command.path("payload").path("until").stringValue()).isEqualTo(WINDOW_END.toString());
    }

    @Test
    @DisplayName("scopes the processed-events lookup to the people-contact owner and the manifest window")
    void lookupIsScopedToOwnerAndWindow() {
        replicaHas(List.of());
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);

        listener.onManifest(manifestFor(List.of()));

        verify(processedEvents).findEventIdsInRange(owner.capture(), anyString(), anyString());
        assertThat(owner.getValue()).isEqualTo(PeopleContactEventsListener.OWNER);
    }

    @Test
    @DisplayName("drops an unparseable manifest without querying or replaying")
    void unparseableManifest_isDropped() {
        listener.onManifest("not a manifest");

        verify(processedEvents, never()).findEventIdsInRange(anyString(), anyString(), anyString());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("swallows a failure to publish the replay request — the next manifest re-detects the drift")
    void whenReplayPublishFails_doesNotPropagate() {
        replicaHas(List.of());
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("broker down"));

        listener.onManifest(manifestFor(List.of("id-1")));

        // The drift metric already fired, so the signal is not lost.
        assertThat(driftCount()).isEqualTo(1d);
    }

    @Test
    @DisplayName("detects drift and requests a replay when no MeterRegistry is available")
    void worksWithoutMeterRegistry() {
        PeopleContactManifestListener withoutMetrics = newListener(null);
        replicaHas(List.of());
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(mock(SendResult.class)));

        withoutMetrics.onManifest(manifestFor(List.of("id-1")));

        verify(kafkaTemplate).send(eq(COMMANDS_TOPIC), anyString(), anyString());
    }
}
