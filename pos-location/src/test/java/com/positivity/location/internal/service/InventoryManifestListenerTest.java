package com.positivity.location.internal.service;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.location.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * Consumer-side reconciliation of the inventory replica (ADR-0044 §4, #899): a matching manifest
 * is silent, a mismatch increments {@code replica.drift} and asks the owner to replay the window.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InventoryManifestListener")
class InventoryManifestListenerTest {

    private static final Instant WINDOW_START = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-03-01T01:00:00Z");
    private static final String COMMANDS_TOPIC = "inventory.commands.v1";
    private static final List<String> EVENT_IDS =
            List.of("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fee01", "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fee02");

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MeterRegistry meterRegistry;
    private InventoryManifestListener listener;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        listener = newListener(meterRegistry);
    }

    private InventoryManifestListener newListener(MeterRegistry registry) {
        InventoryManifestListener created = new InventoryManifestListener(
                processedEventRepository, kafkaTemplate, objectMapper, objectProviderOf(registry));
        ReflectionTestUtils.setField(created, "locationCommandsTopic", COMMANDS_TOPIC);
        return created;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> objectProviderOf(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    private String envelope(long eventCount, String checksum) {
        ReconciliationManifestV1 manifest = new ReconciliationManifestV1(
                TENANT_A,
                WINDOW_START,
                WINDOW_END,
                eventCount,
                checksum,
                Map.of("inventory.stock.changed", eventCount));
        return objectMapper.writeValueAsString(Map.of("payload", manifest));
    }

    private double driftCount() {
        return java.util.Optional.ofNullable(meterRegistry
                        .find("replica.drift")
                        .tag("owner", "inventory")
                        .counter())
                .map(io.micrometer.core.instrument.Counter::count)
                .orElse(0d);
    }

    @Test
    void staysSilentWhenTheReplicaMatchesTheManifest() {
        when(processedEventRepository.findEventIdsInRange(anyString(), any(), anyString(), anyString()))
                .thenReturn(EVENT_IDS);

        listener.onManifest(envelope(EVENT_IDS.size(), ReconciliationManifestV1.checksumOf(EVENT_IDS)));

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        assertThat(driftCount()).isZero();
    }

    @Test
    void requestsAReplayWhenTheCountDoesNotMatch() {
        when(processedEventRepository.findEventIdsInRange(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of(EVENT_IDS.get(0)));

        listener.onManifest(envelope(EVENT_IDS.size(), ReconciliationManifestV1.checksumOf(EVENT_IDS)));

        ProducerRecord<String, String> replay = capturedReplay();

        assertThat(replay.topic()).isEqualTo(COMMANDS_TOPIC);

        assertThat(replay.key()).isEqualTo(WINDOW_START.toString());
        assertThat(replay.value()).contains("inventory.outbox.replay-requested");
        assertThat(replay.value()).contains(WINDOW_START.toString()).contains(WINDOW_END.toString());
        assertThat(driftCount()).isEqualTo(1.0d);
    }

    @Test
    void requestsAReplayWhenTheChecksumDoesNotMatch() {
        when(processedEventRepository.findEventIdsInRange(anyString(), any(), anyString(), anyString()))
                .thenReturn(EVENT_IDS);

        listener.onManifest(
                envelope(EVENT_IDS.size(), "0000000000000000000000000000000000000000000000000000000000000000"));

        verify(kafkaTemplate).send(replayOn(COMMANDS_TOPIC));
        assertThat(driftCount()).isEqualTo(1.0d);
    }

    @Test
    void scopesTheLocalRecountToTheInventoryOwnerAndTheManifestWindow() {
        when(processedEventRepository.findEventIdsInRange(anyString(), any(), anyString(), anyString()))
                .thenReturn(EVENT_IDS);

        listener.onManifest(envelope(EVENT_IDS.size(), ReconciliationManifestV1.checksumOf(EVENT_IDS)));

        verify(processedEventRepository).findEventIdsInRange(eq("inventory"), eq(TENANT_A), anyString(), anyString());
    }

    @Test
    void dropsAnUnparseableManifestWithoutTouchingTheReplica() {
        listener.onManifest("not json at all");

        verify(processedEventRepository, never()).findEventIdsInRange(any(), any(), any(), any());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    void survivesAFailureToPublishTheReplayRequest() {
        when(processedEventRepository.findEventIdsInRange(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(kafkaTemplate)
                .send(any(ProducerRecord.class));

        listener.onManifest(envelope(EVENT_IDS.size(), ReconciliationManifestV1.checksumOf(EVENT_IDS)));

        // The drift metric still fired; the next manifest re-detects the gap.
        assertThat(driftCount()).isEqualTo(1.0d);
    }

    @Test
    void worksWithoutAMeterRegistry() {
        InventoryManifestListener noMetrics = newListener(null);
        when(processedEventRepository.findEventIdsInRange(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of());

        noMetrics.onManifest(envelope(EVENT_IDS.size(), ReconciliationManifestV1.checksumOf(EVENT_IDS)));

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
}
