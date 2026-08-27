package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.repository.ProcessedEventRepository;
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
 * Unit tests for {@link InvoiceManifestListener} (ADR-0044 §4, issue #1537 D2). Follows the
 * {@code PeopleContactManifestListener} template: recomputes count + checksum from {@code
 * processed_events}, scoped to the {@code invoice} owner tag {@link InvoiceEventsListener}
 * stamps, and requests a replay on {@code invoice.commands.v1} on mismatch.
 */
@DisplayName("InvoiceManifestListener — replica reconciliation and replay requests")
class InvoiceManifestListenerTest {

    private static final Instant WINDOW_START = Instant.parse("2026-07-08T11:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-07-08T12:00:00Z");
    private static final String COMMANDS_TOPIC = "invoice.commands.v1";

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SimpleMeterRegistry meterRegistry;
    private InvoiceManifestListener listener;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        listener = newListener(meterRegistry);
    }

    @SuppressWarnings("unchecked")
    private InvoiceManifestListener newListener(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        InvoiceManifestListener created =
                new InvoiceManifestListener(processedEvents, kafkaTemplate, objectMapper, provider);
        ReflectionTestUtils.setField(created, "invoiceCommandsTopic", COMMANDS_TOPIC);
        return created;
    }

    private String manifestFor(List<String> eventIds) {
        ReconciliationManifestV1 manifest = new ReconciliationManifestV1(
                WINDOW_START, WINDOW_END, eventIds.size(), ReconciliationManifestV1.checksumOf(eventIds), null);
        return "{\"eventType\":\"invoice.reconciliation.manifest\",\"payload\":"
                + objectMapper.writeValueAsString(manifest) + "}";
    }

    private void replicaHas(List<String> eventIds) {
        when(processedEvents.findEventIdsInRangeForOwner(anyString(), anyString(), anyString()))
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
    @DisplayName("requests exactly one replay command on drift")
    void requestsExactlyOneReplayCommand() {
        replicaHas(List.of("id-1"));

        listener.onManifest(manifestFor(List.of("id-1", "id-2")));

        verify(kafkaTemplate, org.mockito.Mockito.times(1)).send(eq(COMMANDS_TOPIC), anyString(), anyString());
    }

    @Test
    @DisplayName("scopes the processed-events lookup to the invoice owner and the manifest window")
    void lookupIsScopedToOwnerAndWindow() {
        replicaHas(List.of());
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);

        listener.onManifest(manifestFor(List.of()));

        verify(processedEvents).findEventIdsInRangeForOwner(owner.capture(), anyString(), anyString());
        assertThat(owner.getValue()).isEqualTo(InvoiceEventsListener.OWNER);
    }

    @Test
    @DisplayName("drops an unparseable manifest without querying or replaying")
    void unparseableManifest_isDropped() {
        listener.onManifest("not a manifest");

        verify(processedEvents, never()).findEventIdsInRangeForOwner(anyString(), anyString(), anyString());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("swallows a failure to publish the replay request — the next manifest re-detects the drift")
    void whenReplayPublishFails_doesNotPropagate() {
        replicaHas(List.of());
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("broker down"));

        listener.onManifest(manifestFor(List.of("id-1")));

        assertThat(driftCount()).isEqualTo(1d);
    }

    @Test
    @DisplayName("the replay command carries the window bounds, correct type, and is keyed by window start")
    void replayCommand_carriesWindowBounds() {
        replicaHas(List.of());
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);

        listener.onManifest(manifestFor(List.of("id-1")));

        verify(kafkaTemplate).send(eq(COMMANDS_TOPIC), key.capture(), body.capture());
        assertThat(key.getValue()).isEqualTo(WINDOW_START.toString());

        JsonNode command = objectMapper.readTree(body.getValue());
        assertThat(command.path("commandType").stringValue()).isEqualTo("invoice.outbox.replay-requested");
        assertThat(command.path("payload").path("since").stringValue()).isEqualTo(WINDOW_START.toString());
        assertThat(command.path("payload").path("until").stringValue()).isEqualTo(WINDOW_END.toString());
    }

    @Test
    @DisplayName("redelivery of the same manifest is idempotent: each delivery independently detects the same drift")
    void redeliveryIsIdempotent() {
        replicaHas(List.of("id-1"));
        String message = manifestFor(List.of("id-1", "id-2"));

        listener.onManifest(message);
        listener.onManifest(message);

        verify(kafkaTemplate, org.mockito.Mockito.times(2)).send(eq(COMMANDS_TOPIC), anyString(), anyString());
        assertThat(driftCount()).isEqualTo(2d);
    }

    @Test
    @DisplayName("agrees on an empty window rather than treating zero as drift")
    void whenBothEmpty_requestsNothing() {
        replicaHas(List.of());

        listener.onManifest(manifestFor(List.of()));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("detects drift and requests a replay when no MeterRegistry is available")
    void worksWithoutMeterRegistry() {
        InvoiceManifestListener withoutMetrics = newListener(null);
        replicaHas(List.of());
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(mock(SendResult.class)));

        withoutMetrics.onManifest(manifestFor(List.of("id-1")));

        verify(kafkaTemplate).send(eq(COMMANDS_TOPIC), anyString(), anyString());
    }
}
