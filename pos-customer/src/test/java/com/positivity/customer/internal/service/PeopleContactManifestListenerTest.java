package com.positivity.customer.internal.service;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.producer.ProducerRecord;
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
                TENANT_A,
                WINDOW_START,
                WINDOW_END,
                eventIds.size(),
                ReconciliationManifestV1.checksumOf(eventIds),
                null);
        return "{\"eventType\":\"people-contact.reconciliation.manifest\",\"payload\":"
                + objectMapper.writeValueAsString(manifest) + "}";
    }

    private void replicaHas(List<String> eventIds) {
        when(processedEvents.findEventIdsInRange(anyString(), any(), anyString(), anyString()))
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

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        assertThat(driftCount()).isZero();
    }

    @Test
    @DisplayName("requests a replay when the replica is missing events")
    void whenReplicaShort_requestsReplay() {
        replicaHas(List.of("id-1"));

        listener.onManifest(manifestFor(List.of("id-1", "id-2")));

        verify(kafkaTemplate).send(replayOn(COMMANDS_TOPIC));
        assertThat(driftCount()).isEqualTo(1d);
    }

    @Test
    @DisplayName("requests a replay when the count matches but the event ids do not")
    void whenChecksumDiffersAtSameCount_requestsReplay() {
        replicaHas(List.of("id-1", "id-DIFFERENT"));

        // Count alone would pass here; only the checksum catches a substituted id.
        listener.onManifest(manifestFor(List.of("id-1", "id-2")));

        verify(kafkaTemplate).send(replayOn(COMMANDS_TOPIC));
        assertThat(driftCount()).isEqualTo(1d);
    }

    @Test
    @DisplayName("requests a replay when the replica holds events the manifest does not list")
    void whenReplicaHasExtra_requestsReplay() {
        replicaHas(List.of("id-1", "id-2", "id-3"));

        listener.onManifest(manifestFor(List.of("id-1", "id-2")));

        verify(kafkaTemplate).send(replayOn(COMMANDS_TOPIC));
    }

    @Test
    @DisplayName("agrees on an empty window rather than treating zero as drift")
    void whenBothEmpty_requestsNothing() {
        replicaHas(List.of());

        listener.onManifest(manifestFor(List.of()));

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("the replay command carries the window bounds and is keyed by window start")
    void replayCommand_carriesWindowBounds() {
        replicaHas(List.of());

        listener.onManifest(manifestFor(List.of("id-1")));

        ProducerRecord<String, String> replay = capturedReplay();

        assertThat(replay.topic()).isEqualTo(COMMANDS_TOPIC);
        assertThat(replay.key()).isEqualTo(WINDOW_START.toString());

        JsonNode command = objectMapper.readTree(replay.value());
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

        verify(processedEvents).findEventIdsInRange(owner.capture(), eq(TENANT_A), anyString(), anyString());
        assertThat(owner.getValue()).isEqualTo(PeopleContactEventsListener.OWNER);
    }

    @Test
    @DisplayName("drops an unparseable manifest without querying or replaying")
    void unparseableManifest_isDropped() {
        listener.onManifest("not a manifest");

        verify(processedEvents, never()).findEventIdsInRange(anyString(), any(), anyString(), anyString());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("swallows a failure to publish the replay request — the next manifest re-detects the drift")
    void whenReplayPublishFails_doesNotPropagate() {
        replicaHas(List.of());
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(new RuntimeException("broker down"));

        listener.onManifest(manifestFor(List.of("id-1")));

        // The drift metric already fired, so the signal is not lost.
        assertThat(driftCount()).isEqualTo(1d);
    }

    @Test
    @DisplayName("detects drift and requests a replay when no MeterRegistry is available")
    void worksWithoutMeterRegistry() {
        PeopleContactManifestListener withoutMetrics = newListener(null);
        replicaHas(List.of());
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(mock(SendResult.class)));

        withoutMetrics.onManifest(manifestFor(List.of("id-1")));

        verify(kafkaTemplate).send(replayOn(COMMANDS_TOPIC));
    }

    /** The one replay command handed to Kafka: it must ride the manifest's tenant header (ADR-0062 §3). */
    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> capturedReplay() {
        ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(record.capture());
        assertThat(TenantKafkaHeaders.read(record.getValue().headers())).contains(TENANT_A);
        return record.getValue();
    }

    /** Matches a replay command routed to {@code topic} under the manifest's tenant header. */
    private static ProducerRecord<String, String> replayOn(String topic) {
        return org.mockito.ArgumentMatchers.argThat(
                (ProducerRecord<String, String> record) -> topic.equals(record.topic())
                        && TenantKafkaHeaders.read(record.headers())
                                .filter(TENANT_A::equals)
                                .isPresent());
    }

    @Test
    @DisplayName("skips a manifest without tenantId instead of reading it as any tenant's (WS4-3)")
    void skipsAManifestWithoutTenant() {
        // Published before manifests were per tenant (ADR-0062 WS4-3): it summarised every
        // tenant's rows at once, so no single tenant's ledger can be compared against it.
        String legacy = """
                {"eventType":"x.reconciliation.manifest",
                 "payload":{"windowStartUtc":"%s","windowEndUtc":"%s","eventCount":2,
                   "eventIdsChecksum":"owner-checksum","eventTypeCounts":null}}
                """.formatted(WINDOW_START, WINDOW_END);

        listener.onManifest(legacy);

        verify(processedEvents, never()).findEventIdsInRange(any(), any(), any(), any());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        assertThat(meterRegistry.find("replica.drift").counters()).isEmpty();
        var skipped = meterRegistry
                .find("replica.manifest.skipped")
                .tag("reason", "missing_tenant")
                .counter();
        assertThat(skipped).isNotNull();
        assertThat(skipped.count()).isEqualTo(1.0);
    }
}
