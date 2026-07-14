package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import com.positivity.workorder.internal.service.InvoiceManifestListener;
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

class InvoiceManifestListenerTest {

    private static final Instant WINDOW_START = Instant.parse("2026-07-14T10:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-07-14T11:00:00Z");
    private static final String IN_WINDOW_ID_1 = eventIdAt(WINDOW_START.plusSeconds(60), 1);
    private static final String IN_WINDOW_ID_2 = eventIdAt(WINDOW_START.plusSeconds(120), 2);

    private final ProcessedEventRepository repository = mock(ProcessedEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private InvoiceManifestListener listener;

    @BeforeEach
    void setUp() {
        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);
        listener = new InvoiceManifestListener(repository, kafkaTemplate, objectMapper, meterRegistryProvider);
        ReflectionTestUtils.setField(listener, "invoiceCommandsTopic", "invoice.commands.v1");
    }

    /** UUIDv7-shaped id whose embedded timestamp is {@code at}. */
    private static String eventIdAt(Instant at, int suffix) {
        long millis = at.toEpochMilli();
        return String.format("%08x-%04x-7000-8000-%012x", millis >>> 16, millis & 0xFFFF, suffix);
    }

    private String manifestMessage(long eventCount, String checksum) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "invoice.reconciliation.manifest",
                  "schemaVersion": 1,
                  "aggregateId": "00000000-0000-0000-0000-000000000001",
                  "aggregateVersion": 1,
                  "occurredAtUtc": "2026-07-14T11:05:00Z",
                  "sourceService": "pos-invoice",
                  "payload": {
                    "windowStartUtc": "%s",
                    "windowEndUtc": "%s",
                    "eventCount": %d,
                    "eventIdsChecksum": "%s",
                    "eventTypeCounts": {"InvoiceUpdated": %d}
                  }
                }
                """.formatted(
                        eventIdAt(Instant.parse("2026-07-14T11:05:00Z"), 9),
                        WINDOW_START,
                        WINDOW_END,
                        eventCount,
                        checksum,
                        eventCount);
    }

    private double driftCount() {
        return meterRegistry
                .get("replica.drift")
                .tags("owner", "invoice")
                .counter()
                .count();
    }

    @Test
    @DisplayName("Matching count and checksum → no drift, no replay request")
    void matchingManifestIsQuiet() {
        List<String> ids = List.of(IN_WINDOW_ID_1, IN_WINDOW_ID_2);
        when(repository.findEventIdsInRange(eq("invoice"), anyString(), anyString()))
                .thenReturn(ids);

        listener.onManifest(manifestMessage(2, ReconciliationManifestV1.checksumOf(ids)));

        assertThat(driftCount()).isZero();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Missing event → drift metric + replay request for the window")
    void missingEventTriggersDriftAndReplay() throws Exception {
        // Owner saw two events, we only recorded one.
        when(repository.findEventIdsInRange(eq("invoice"), anyString(), anyString()))
                .thenReturn(List.of(IN_WINDOW_ID_1));

        listener.onManifest(
                manifestMessage(2, ReconciliationManifestV1.checksumOf(List.of(IN_WINDOW_ID_1, IN_WINDOW_ID_2))));

        assertThat(driftCount()).isEqualTo(1.0);
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("invoice.commands.v1"), anyString(), command.capture());
        JsonNode json = objectMapper.readTree(command.getValue());
        assertThat(json.path("commandType").stringValue()).isEqualTo("invoice.outbox.replay-requested");
        assertThat(json.path("payload").path("since").stringValue()).isEqualTo(WINDOW_START.toString());
        assertThat(json.path("payload").path("until").stringValue()).isEqualTo(WINDOW_END.toString());
    }

    @Test
    @DisplayName("Same count but different ids (checksum mismatch) → drift")
    void checksumMismatchTriggersDrift() {
        when(repository.findEventIdsInRange(eq("invoice"), anyString(), anyString()))
                .thenReturn(List.of(IN_WINDOW_ID_1));

        listener.onManifest(manifestMessage(1, ReconciliationManifestV1.checksumOf(List.of(IN_WINDOW_ID_2))));

        assertThat(driftCount()).isEqualTo(1.0);
        verify(kafkaTemplate).send(eq("invoice.commands.v1"), anyString(), anyString());
    }

    @Test
    @DisplayName("Window bounds passed to the repository are the UUIDv7 range of the manifest window")
    void queriesRepositoryWithWindowBounds() {
        when(repository.findEventIdsInRange(eq("invoice"), anyString(), anyString()))
                .thenReturn(List.of());

        listener.onManifest(manifestMessage(0, ReconciliationManifestV1.checksumOf(List.of())));

        ArgumentCaptor<String> lower = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> upper = ArgumentCaptor.forClass(String.class);
        verify(repository).findEventIdsInRange(eq("invoice"), lower.capture(), upper.capture());
        assertThat(lower.getValue()).isLessThan(IN_WINDOW_ID_1).isLessThan(upper.getValue());
        assertThat(upper.getValue()).isGreaterThan(IN_WINDOW_ID_2);
        assertThat(driftCount()).isZero();
    }

    @Test
    @DisplayName("Unparseable manifests are dropped without drift or replay")
    void unparseableManifestIsDropped() {
        listener.onManifest("{not json");
        listener.onManifest("{\"payload\": {\"windowStartUtc\": \"oops\"}}");

        assertThat(driftCount()).isZero();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(repository, never()).findEventIdsInRange(anyString(), anyString(), anyString());
    }
}
