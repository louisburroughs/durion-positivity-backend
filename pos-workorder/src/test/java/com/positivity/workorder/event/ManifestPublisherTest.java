package com.positivity.workorder.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.workorder.internal.config.ManifestPublisher;
import com.positivity.workorder.internal.entity.OutboxEvent;
import com.positivity.workorder.internal.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

class ManifestPublisherTest {

    // 12:10 UTC: the [11:00, 12:00) window closed more than grace (5m) ago.
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-08T12:10:00Z"), ZoneOffset.UTC);
    private static final Instant WINDOW_START = Instant.parse("2026-07-08T11:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-07-08T12:00:00Z");

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<MeterRegistry> meterRegistry = mock(ObjectProvider.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ManifestPublisher publisher;

    @BeforeEach
    void setUp() {
        when(meterRegistry.getIfAvailable()).thenReturn(null);
        publisher = new ManifestPublisher(repository, kafkaTemplate, objectMapper, TEST_CLOCK, meterRegistry);
        ReflectionTestUtils.setField(publisher, "eventsTopic", "workorder.events.v1");
        ReflectionTestUtils.setField(publisher, "manifestTopic", "workorder.manifest.v1");
        ReflectionTestUtils.setField(publisher, "window", Duration.ofHours(1));
        ReflectionTestUtils.setField(publisher, "grace", Duration.ofMinutes(5));
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 1000L);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    /** UUIDv7-shaped id whose embedded timestamp is {@code at}. */
    private static String eventIdAt(Instant at, int suffix) {
        long millis = at.toEpochMilli();
        return String.format("%08x-%04x-7000-8000-%012x", millis >>> 16, millis & 0xFFFF, suffix);
    }

    private OutboxEvent row(String eventId, String eventType, Instant createdAt) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .topic("workorder.events.v1")
                .recordKey(eventId)
                .payload("{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType + "\",\"payload\":{}}")
                .createdAt(createdAt)
                .publishedAt(createdAt.plusSeconds(1))
                .build();
    }

    @Test
    @DisplayName("Publishes a manifest for the latest closed window with count, checksum, and per-type counts")
    void publishesManifestForClosedWindow() throws Exception {
        String inWindow1 = eventIdAt(WINDOW_START.plusSeconds(60), 1);
        String inWindow2 = eventIdAt(WINDOW_START.plusSeconds(120), 2);
        // Fetched by the padded createdAt query but outside the window by eventId timestamp.
        String beforeWindow = eventIdAt(WINDOW_START.minusMillis(1), 3);
        when(repository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(
                        row(beforeWindow, "VehicleUpdated", WINDOW_START.minusMillis(1)),
                        row(inWindow1, "VehicleUpdated", WINDOW_START.plusSeconds(60)),
                        row(inWindow2, "PartyNoteAdded", WINDOW_START.plusSeconds(120))));

        publisher.publishDueManifest();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate)
                .send(org.mockito.ArgumentMatchers.eq("workorder.manifest.v1"), anyString(), json.capture());

        JsonNode envelope = objectMapper.readTree(json.getValue());
        assertThat(envelope.path("eventType").stringValue()).isEqualTo("workorder.reconciliation.manifest");
        assertThat(envelope.path("sourceService").stringValue()).isEqualTo("pos-workorder");
        JsonNode manifest = envelope.path("payload");
        assertThat(manifest.path("windowStartUtc").stringValue()).isEqualTo(WINDOW_START.toString());
        assertThat(manifest.path("windowEndUtc").stringValue()).isEqualTo(WINDOW_END.toString());
        assertThat(manifest.path("eventCount").longValue()).isEqualTo(2);
        assertThat(manifest.path("eventIdsChecksum").stringValue())
                .isEqualTo(ReconciliationManifestV1.checksumOf(List.of(inWindow1, inWindow2)));
        assertThat(manifest.path("eventTypeCounts").path("VehicleUpdated").longValue())
                .isEqualTo(1);
        assertThat(manifest.path("eventTypeCounts").path("PartyNoteAdded").longValue())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Publishes zero-count manifests so consumers can alert on manifest absence")
    void publishesEmptyWindowManifest() throws Exception {
        when(repository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());

        publisher.publishDueManifest();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), anyString(), json.capture());
        JsonNode manifest = objectMapper.readTree(json.getValue()).path("payload");
        assertThat(manifest.path("eventCount").longValue()).isZero();
        assertThat(manifest.path("eventIdsChecksum").stringValue())
                .isEqualTo(ReconciliationManifestV1.checksumOf(List.of()));
    }

    @Test
    @DisplayName("Does not re-publish the same window twice in a row")
    void skipsAlreadyPublishedWindow() {
        when(repository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());

        publisher.publishDueManifest();
        publisher.publishDueManifest();

        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Retries the window on the next run when the send fails")
    void retriesWindowAfterSendFailure() {
        when(repository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishDueManifest();
        publisher.publishDueManifest();

        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Waits for the grace period before publishing a just-closed window")
    void respectsGracePeriod() {
        ReflectionTestUtils.setField(publisher, "grace", Duration.ofMinutes(15));
        when(repository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());

        publisher.publishDueManifest();

        // 12:10 with 15m grace → [11:00, 12:00) not yet eligible; the eligible window is the
        // previous one, so the manifest published covers [10:00, 11:00).
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), anyString(), json.capture());
        JsonNode manifest = objectMapper.readTree(json.getValue()).path("payload");
        assertThat(manifest.path("windowEndUtc").stringValue()).isEqualTo("2026-07-08T11:00:00Z");
    }

    @Test
    @DisplayName("Rows without a parseable eventId are excluded rather than failing the manifest")
    void skipsRowsWithoutEventId() throws Exception {
        OutboxEvent broken = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .topic("workorder.events.v1")
                .recordKey("k")
                .payload("{\"eventType\":\"VehicleUpdated\"}")
                .createdAt(WINDOW_START.plusSeconds(30))
                .publishedAt(WINDOW_START.plusSeconds(31))
                .build();
        when(repository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(broken));

        publisher.publishDueManifest();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), anyString(), json.capture());
        assertThat(objectMapper
                        .readTree(json.getValue())
                        .path("payload")
                        .path("eventCount")
                        .longValue())
                .isZero();
    }
}
