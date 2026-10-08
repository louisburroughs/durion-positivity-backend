package com.positivity.order.internal.service;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.order.internal.repository.ProcessedEventRepository;
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
 * CAP:550 S24 (#2517; AC 11 for pos-order; ADR-0044 §4): pos-order compares pos-supplier's per-tenant manifest with
 * its processed-events ledger (owner {@code supplier}) and asks for a replay of the window on drift.
 */
@DisplayName("SupplierManifestListener — drift check on supplier.manifest.v1 (S24, #2517)")
class SupplierManifestListenerTest {

    private static final Instant WINDOW_START = Instant.parse("2026-10-07T10:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-10-07T11:00:00Z");
    private static final String COMMANDS_TOPIC = "supplier.commands.v1";

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private SupplierManifestListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meterRegistry);
        listener = new SupplierManifestListener(processedEvents, kafkaTemplate, objectMapper, provider);
        ReflectionTestUtils.setField(listener, "supplierCommandsTopic", COMMANDS_TOPIC);
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

    private void ledgerHas(List<String> eventIds) {
        when(processedEvents.findEventIdsInRange(anyString(), any(), anyString(), anyString()))
                .thenReturn(eventIds);
    }

    @Test
    @DisplayName("a matching window requests nothing")
    void matchRequestsNothing() {
        ledgerHas(List.of("e-1", "e-2"));

        listener.onManifest(manifestFor(List.of("e-1", "e-2")));

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(processedEvents).findEventIdsInRange(eq("supplier"), eq(TENANT_A), anyString(), anyString());
    }

    @Test
    @DisplayName("drift counts replica.drift and sends one tenant-stamped replay request for the window")
    @SuppressWarnings("unchecked")
    void driftRequestsReplay() {
        ledgerHas(List.of("e-1"));

        listener.onManifest(manifestFor(List.of("e-1", "e-2")));

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(sent.capture());
        ProducerRecord<String, String> replay = sent.getValue();
        assertThat(replay.topic()).isEqualTo(COMMANDS_TOPIC);
        JsonNode command = objectMapper.readTree(replay.value());
        assertThat(command.path("commandType").stringValue()).isEqualTo("supplier.outbox.replay-requested");
        assertThat(command.path("payload").path("since").stringValue()).isEqualTo(WINDOW_START.toString());
        assertThat(command.path("payload").path("until").stringValue()).isEqualTo(WINDOW_END.toString());
        assertThat(TenantKafkaHeaders.read(replay.headers())).contains(TENANT_A);
        assertThat(meterRegistry
                        .get("replica.drift")
                        .tag("owner", "supplier")
                        .tag("tenant", TENANT_A.toString())
                        .counter()
                        .count())
                .isEqualTo(1d);
    }

    @Test
    @DisplayName("an unparseable manifest is dropped")
    void unparseableDropped() {
        listener.onManifest("not json");

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("a manifest without a tenant is skipped and counted, and requests nothing")
    void tenantlessManifestSkipped() {
        ReconciliationManifestV1 manifest = new ReconciliationManifestV1(
                null, WINDOW_START, WINDOW_END, 1, ReconciliationManifestV1.checksumOf(List.of("e-1")), null);
        String message = "{\"eventType\":\"supplier.reconciliation.manifest\",\"payload\":"
                + objectMapper.writeValueAsString(manifest) + "}";

        listener.onManifest(message);

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(processedEvents, never()).findEventIdsInRange(anyString(), any(), anyString(), anyString());
        assertThat(meterRegistry
                        .get("replica.manifest.skipped")
                        .tag("owner", "supplier")
                        .tag("reason", "missing_tenant")
                        .counter()
                        .count())
                .isEqualTo(1d);
    }
}
