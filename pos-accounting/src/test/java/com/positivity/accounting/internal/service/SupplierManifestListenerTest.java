package com.positivity.accounting.internal.service;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
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
 * Unit tests for {@link SupplierManifestListener} (ADR-0044 §4; CAP:550 S24, #2517): recomputes count + checksum from
 * {@code processed_events}, scoped to the {@code supplier} owner tag {@link SupplierEventsListener} stamps, and
 * requests a replay on {@code supplier.commands.v1} on mismatch.
 */
@DisplayName("SupplierManifestListener — supplier.events.v1 reconciliation and replay requests")
class SupplierManifestListenerTest {

    private static final Instant WINDOW_START = Instant.parse("2026-07-08T11:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-07-08T12:00:00Z");
    private static final String COMMANDS_TOPIC = "supplier.commands.v1";

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SimpleMeterRegistry meterRegistry;
    private SupplierManifestListener listener;

    @BeforeEach
    void setUp() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        meterRegistry = new SimpleMeterRegistry();
        listener = newListener(meterRegistry);
    }

    @SuppressWarnings("unchecked")
    private SupplierManifestListener newListener(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        SupplierManifestListener created =
                new SupplierManifestListener(processedEvents, kafkaTemplate, objectMapper, provider);
        ReflectionTestUtils.setField(created, "supplierCommandsTopic", COMMANDS_TOPIC);
        return created;
    }

    private String manifestFor(List<String> eventIds) {
        ReconciliationManifestV1 manifest = new ReconciliationManifestV1(
                TENANT_A,
                WINDOW_START,
                WINDOW_END,
                eventIds.size(),
                ReconciliationManifestV1.checksumOf(eventIds),
                null);
        return "{\"eventType\":\"supplier.reconciliation.manifest\",\"payload\":"
                + objectMapper.writeValueAsString(manifest) + "}";
    }

    private void replicaHas(List<String> eventIds) {
        when(processedEvents.findEventIdsInRangeForOwner(anyString(), any(), anyString(), anyString()))
                .thenReturn(eventIds);
    }

    private double driftCount() {
        var counter = meterRegistry.find("replica.drift").counter();
        return counter == null ? 0d : counter.count();
    }

    @Test
    @DisplayName("stays silent when the ledger matches the manifest")
    void whenLedgerMatches_requestsNothing() {
        List<String> ids = List.of("id-1", "id-2");
        replicaHas(ids);

        listener.onManifest(manifestFor(ids));

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        assertThat(driftCount()).isZero();
    }

    @Test
    @DisplayName("requests one replay of the window, under the manifest's tenant, when the ledger is missing facts")
    void whenLedgerShort_requestsReplay() {
        replicaHas(List.of("id-1"));

        listener.onManifest(manifestFor(List.of("id-1", "id-2")));

        ProducerRecord<String, String> replay = capturedReplay();
        assertThat(replay.topic()).isEqualTo(COMMANDS_TOPIC);
        assertThat(replay.key()).isEqualTo(WINDOW_START.toString());
        JsonNode command = objectMapper.readTree(replay.value());
        assertThat(command.path("commandType").stringValue()).isEqualTo("supplier.outbox.replay-requested");
        assertThat(command.path("payload").path("since").stringValue()).isEqualTo(WINDOW_START.toString());
        assertThat(command.path("payload").path("until").stringValue()).isEqualTo(WINDOW_END.toString());
        assertThat(meterRegistry
                        .find("replica.drift")
                        .tag("owner", "supplier")
                        .tag("tenant", TENANT_A.toString())
                        .counter()
                        .count())
                .isEqualTo(1d);
    }

    @Test
    @DisplayName("scopes the processed-events lookup to the supplier owner, the manifest's tenant and its window")
    void lookupIsScopedToOwnerTenantAndWindow() {
        replicaHas(List.of());

        listener.onManifest(manifestFor(List.of()));

        verify(processedEvents)
                .findEventIdsInRangeForOwner(
                        eq(SupplierEventsListener.OWNER),
                        eq(TENANT_A),
                        eq(UuidV7Timestamps.minStringAt(WINDOW_START)),
                        eq(UuidV7Timestamps.minStringAt(WINDOW_END)));
    }

    @Test
    @DisplayName("drops an unparseable manifest without querying or replaying")
    void unparseableManifest_isDropped() {
        listener.onManifest("not a manifest");

        verify(processedEvents, never()).findEventIdsInRangeForOwner(anyString(), any(), anyString(), anyString());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("propagates a failed replay publish so the container redelivers the manifest (#2452)")
    void whenReplayPublishFails_propagates() {
        replicaHas(List.of());
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(new RuntimeException("broker down"));

        assertThatThrownBy(() -> listener.onManifest(manifestFor(List.of("id-1"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("broker down");
        assertThat(driftCount()).isEqualTo(1d);
    }

    @Test
    @DisplayName("detects drift and requests a replay when no MeterRegistry is available")
    void worksWithoutMeterRegistry() {
        SupplierManifestListener withoutMetrics = newListener(null);
        replicaHas(List.of());

        withoutMetrics.onManifest(manifestFor(List.of("id-1")));

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, String> r) -> COMMANDS_TOPIC.equals(r.topic())));
    }

    @Test
    @DisplayName("skips a manifest without tenantId instead of reading it as any tenant's")
    void skipsAManifestWithoutTenant() {
        String legacy = """
                {"eventType":"supplier.reconciliation.manifest",
                 "payload":{"windowStartUtc":"%s","windowEndUtc":"%s","eventCount":2,
                   "eventIdsChecksum":"owner-checksum","eventTypeCounts":null}}
                """.formatted(WINDOW_START, WINDOW_END);

        listener.onManifest(legacy);

        verify(processedEvents, never()).findEventIdsInRangeForOwner(any(), any(), any(), any());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        assertThat(meterRegistry
                        .find("replica.manifest.skipped")
                        .tag("owner", "supplier")
                        .tag("reason", "missing_tenant")
                        .counter()
                        .count())
                .isEqualTo(1d);
    }

    /** The one replay command handed to Kafka: it must ride the manifest's tenant header (ADR-0062 §3). */
    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> capturedReplay() {
        ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(record.capture());
        assertThat(TenantKafkaHeaders.read(record.getValue().headers())).contains(TENANT_A);
        return record.getValue();
    }
}
