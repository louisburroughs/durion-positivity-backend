package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
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
 * Reconciliation contract of the catalog.manifest.v1 listener (odoo-parity B1, #1033; #1537):
 * recompute count + checksum from processed_events (owner catalog); on drift, increment the metric
 * AND request replay over catalog.commands.v1 — pos-catalog gained a replay-only command listener
 * in #1537, closing the #1023 gap that previously left this module unable to ask for repair.
 */
class CatalogManifestListenerTest {

    private static final Instant WINDOW_START = Instant.parse("2026-07-21T10:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-07-21T11:00:00Z");
    private static final String IN_WINDOW_ID_1 = eventIdAt(WINDOW_START.plusSeconds(60), 1);
    private static final String IN_WINDOW_ID_2 = eventIdAt(WINDOW_START.plusSeconds(120), 2);
    private static final String COMMANDS_TOPIC = "catalog.commands.v1";

    private final ProcessedEventRepository repository = mock(ProcessedEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private CatalogManifestListener listener;

    @BeforeEach
    void setUp() {
        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);
        listener = new CatalogManifestListener(repository, kafkaTemplate, objectMapper, meterRegistryProvider);
        ReflectionTestUtils.setField(listener, "catalogCommandsTopic", COMMANDS_TOPIC);
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
                  "eventType": "catalog.reconciliation.manifest",
                  "schemaVersion": 1,
                  "aggregateId": "00000000-0000-0000-0000-000000000001",
                  "aggregateVersion": 1,
                  "occurredAtUtc": "2026-07-21T11:05:00Z",
                  "sourceService": "pos-catalog",
                  "payload": {
                    "windowStartUtc": "%s",
                    "windowEndUtc": "%s",
                    "eventCount": %d,
                    "eventIdsChecksum": "%s",
                    "eventTypeCounts": {"catalog.product.updated": %d}
                  }
                }
                """.formatted(
                        eventIdAt(Instant.parse("2026-07-21T11:05:00Z"), 9),
                        WINDOW_START,
                        WINDOW_END,
                        eventCount,
                        checksum,
                        eventCount);
    }

    private double driftCount() {
        return meterRegistry
                .get("replica.drift")
                .tags("owner", "catalog")
                .counter()
                .count();
    }

    @Test
    @DisplayName("Matching count and checksum → no drift, no replay command")
    void matchingManifestIsQuiet() {
        List<String> ids = List.of(IN_WINDOW_ID_1, IN_WINDOW_ID_2);
        when(repository.findEventIdsInRange(eq("catalog"), anyString(), anyString()))
                .thenReturn(ids);

        listener.onManifest(manifestMessage(2, ReconciliationManifestV1.checksumOf(ids)));

        assertThat(driftCount()).isZero();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Missing event → drift metric AND a replay command is published (#1537)")
    void missingEventTriggersDriftAndReplay() {
        // Owner saw two events, we only recorded one.
        when(repository.findEventIdsInRange(eq("catalog"), anyString(), anyString()))
                .thenReturn(List.of(IN_WINDOW_ID_1));

        listener.onManifest(
                manifestMessage(2, ReconciliationManifestV1.checksumOf(List.of(IN_WINDOW_ID_1, IN_WINDOW_ID_2))));

        assertThat(driftCount()).isEqualTo(1.0);
        verify(kafkaTemplate).send(eq(COMMANDS_TOPIC), anyString(), anyString());
    }

    @Test
    @DisplayName("Checksum mismatch at equal counts → drift metric AND a replay command")
    void checksumMismatchTriggersDriftAndReplay() {
        when(repository.findEventIdsInRange(eq("catalog"), anyString(), anyString()))
                .thenReturn(List.of(IN_WINDOW_ID_1));

        listener.onManifest(manifestMessage(1, ReconciliationManifestV1.checksumOf(List.of(IN_WINDOW_ID_2))));

        assertThat(driftCount()).isEqualTo(1.0);
        verify(kafkaTemplate).send(eq(COMMANDS_TOPIC), anyString(), anyString());
    }

    @Test
    @DisplayName("Exactly one replay command is published on drift, carrying the window bounds")
    void replayCommand_carriesWindowBoundsAndIsPublishedOnce() {
        when(repository.findEventIdsInRange(eq("catalog"), anyString(), anyString()))
                .thenReturn(List.of());
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);

        listener.onManifest(manifestMessage(1, ReconciliationManifestV1.checksumOf(List.of(IN_WINDOW_ID_1))));

        verify(kafkaTemplate).send(eq(COMMANDS_TOPIC), key.capture(), body.capture());
        assertThat(key.getValue()).isEqualTo(WINDOW_START.toString());

        JsonNode command = objectMapper.readTree(body.getValue());
        assertThat(command.path("commandType").stringValue()).isEqualTo("catalog.outbox.replay-requested");
        assertThat(command.path("payload").path("since").stringValue()).isEqualTo(WINDOW_START.toString());
        assertThat(command.path("payload").path("until").stringValue()).isEqualTo(WINDOW_END.toString());
    }

    @Test
    @DisplayName("Unparseable manifest is dropped without touching the repository or publishing")
    void unparseableManifestIsDropped() {
        listener.onManifest("not-json");

        verify(repository, never()).findEventIdsInRange(anyString(), anyString(), anyString());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        assertThat(driftCount()).isZero();
    }

    @Test
    @DisplayName("A failed replay publish is swallowed — drift metric already fired, next manifest re-detects")
    void failedReplayPublishIsSwallowed() {
        when(repository.findEventIdsInRange(eq("catalog"), anyString(), anyString()))
                .thenReturn(List.of());
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("broker down"));

        listener.onManifest(manifestMessage(1, ReconciliationManifestV1.checksumOf(List.of(IN_WINDOW_ID_1))));

        assertThat(driftCount()).isEqualTo(1.0);
    }
}
