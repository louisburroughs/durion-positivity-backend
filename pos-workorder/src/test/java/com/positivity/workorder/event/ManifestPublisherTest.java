package com.positivity.workorder.event;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.tenancy.TenantRegistry;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import com.positivity.workorder.internal.config.ManifestPublisher;
import com.positivity.workorder.internal.entity.OutboxEvent;
import com.positivity.workorder.internal.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    private final TenantRegistry tenantRegistry = mock(TenantRegistry.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ManifestPublisher publisher;

    @BeforeEach
    void setUp() {
        when(meterRegistry.getIfAvailable()).thenReturn(null);
        when(tenantRegistry.activeTenantIds()).thenReturn(List.of(TENANT_A));
        publisher = new ManifestPublisher(
                repository, kafkaTemplate, objectMapper, TEST_CLOCK, tenantRegistry, meterRegistry);
        ReflectionTestUtils.setField(publisher, "eventsTopic", "workorder.events.v1");
        ReflectionTestUtils.setField(publisher, "manifestTopic", "workorder.manifest.v1");
        ReflectionTestUtils.setField(publisher, "window", Duration.ofHours(1));
        ReflectionTestUtils.setField(publisher, "grace", Duration.ofMinutes(5));
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 1000L);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    /** UUIDv7-shaped id whose embedded timestamp is {@code at}. */
    private static String eventIdAt(Instant at, int suffix) {
        long millis = at.toEpochMilli();
        return String.format("%08x-%04x-7000-8000-%012x", millis >>> 16, millis & 0xFFFF, suffix);
    }

    private OutboxEvent row(String eventId, String eventType, Instant createdAt) {
        return row(TENANT_A, eventId, eventType, createdAt);
    }

    private OutboxEvent row(UUID tenantId, String eventId, String eventType, Instant createdAt) {
        return OutboxEvent.builder()
                .tenantId(tenantId)
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

        String json = capturedRecords(1).get(0).value();

        JsonNode envelope = objectMapper.readTree(json);
        assertThat(envelope.path("eventType").stringValue()).isEqualTo("workorder.reconciliation.manifest");
        assertThat(envelope.path("sourceService").stringValue()).isEqualTo("pos-workorder");
        assertThat(envelope.path("tenantId").stringValue()).isEqualTo(TENANT_A.toString());
        JsonNode manifest = envelope.path("payload");
        assertThat(manifest.path("tenantId").stringValue()).isEqualTo(TENANT_A.toString());
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
    @DisplayName("Publishes one manifest per tenant in the window, each over that tenant's rows only")
    void publishesOneManifestPerTenant() {
        when(tenantRegistry.activeTenantIds()).thenReturn(List.of(TENANT_A, TENANT_B));
        String a1 = eventIdAt(WINDOW_START.plusSeconds(60), 1);
        String a2 = eventIdAt(WINDOW_START.plusSeconds(120), 2);
        String b1 = eventIdAt(WINDOW_START.plusSeconds(180), 3);
        when(repository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(
                        row(TENANT_A, a1, "VehicleUpdated", WINDOW_START.plusSeconds(60)),
                        row(TENANT_B, b1, "PartyNoteAdded", WINDOW_START.plusSeconds(180)),
                        row(TENANT_A, a2, "VehicleUpdated", WINDOW_START.plusSeconds(120))));

        publisher.publishDueManifest();

        Map<UUID, JsonNode> manifests = capturedManifestsByTenant(2);
        assertThat(manifests).containsOnlyKeys(TENANT_A, TENANT_B);
        assertThat(manifests.get(TENANT_A).path("eventCount").longValue()).isEqualTo(2);
        assertThat(manifests.get(TENANT_A).path("eventIdsChecksum").stringValue())
                .isEqualTo(ReconciliationManifestV1.checksumOf(List.of(a1, a2)));
        assertThat(manifests.get(TENANT_A).path("eventTypeCounts").has("PartyNoteAdded"))
                .isFalse();
        assertThat(manifests.get(TENANT_B).path("eventCount").longValue()).isEqualTo(1);
        assertThat(manifests.get(TENANT_B).path("eventIdsChecksum").stringValue())
                .isEqualTo(ReconciliationManifestV1.checksumOf(List.of(b1)));
        // Per-tenant manifests of one window land on distinct keys.
        assertThat(capturedRecords(2).stream().map(ProducerRecord::key).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Publishes zero-count manifests per active tenant so consumers can alert on manifest absence")
    void publishesEmptyWindowManifestPerTenant() throws Exception {
        when(tenantRegistry.activeTenantIds()).thenReturn(List.of(TENANT_A, TENANT_B));
        when(repository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());

        publisher.publishDueManifest();

        Map<UUID, JsonNode> manifests = capturedManifestsByTenant(2);
        assertThat(manifests).containsOnlyKeys(TENANT_A, TENANT_B);
        for (JsonNode manifest : manifests.values()) {
            assertThat(manifest.path("eventCount").longValue()).isZero();
            assertThat(manifest.path("eventIdsChecksum").stringValue())
                    .isEqualTo(ReconciliationManifestV1.checksumOf(List.of()));
        }
    }

    @Test
    @DisplayName("Does not re-publish the same window twice in a row")
    void skipsAlreadyPublishedWindow() {
        when(repository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());

        publisher.publishDueManifest();
        publisher.publishDueManifest();

        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("Retries the window on the next run when the send fails")
    void retriesWindowAfterSendFailure() {
        when(repository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishDueManifest();
        publisher.publishDueManifest();

        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
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
        String json = capturedRecords(1).get(0).value();
        JsonNode manifest = objectMapper.readTree(json).path("payload");
        assertThat(manifest.path("windowEndUtc").stringValue()).isEqualTo("2026-07-08T11:00:00Z");
    }

    @Test
    @DisplayName("Rows without a parseable eventId are excluded rather than failing the manifest")
    void skipsRowsWithoutEventId() throws Exception {
        OutboxEvent broken = OutboxEvent.builder()
                .tenantId(TENANT_A)
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

        String json = capturedRecords(1).get(0).value();
        assertThat(objectMapper
                        .readTree(json)
                        .path("payload")
                        .path("eventCount")
                        .longValue())
                .isZero();
    }

    @Test
    @DisplayName("Catches up every missed window in order after a scheduler gap (PR #849 review)")
    void catchesUpMissedWindows() {
        when(repository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());
        ReflectionTestUtils.setField(publisher, "lastPublishedWindowEnd", Instant.parse("2026-07-08T09:00:00Z"));

        publisher.publishDueManifest();

        List<String> ends = capturedRecords(3).stream()
                .map(ProducerRecord::value)
                .map(m -> objectMapper
                        .readTree(m)
                        .path("payload")
                        .path("windowEndUtc")
                        .stringValue())
                .toList();
        assertThat(ends).containsExactly("2026-07-08T10:00:00Z", "2026-07-08T11:00:00Z", "2026-07-08T12:00:00Z");
    }

    @Test
    @DisplayName("A malformed outbox row is skipped, not fatal to the window's manifest (PR #849 review)")
    void skipsMalformedRows() {
        OutboxEvent good = row(
                eventIdAt(WINDOW_START.plusSeconds(60), 1),
                "workorder.work-session.started.v1",
                WINDOW_START.plusSeconds(60));
        OutboxEvent badJson = OutboxEvent.builder()
                .tenantId(TENANT_A)
                .id(UUID.randomUUID())
                .topic("workorder.events.v1")
                .recordKey("bad")
                .payload("{not json")
                .createdAt(WINDOW_START.plusSeconds(120))
                .publishedAt(WINDOW_START.plusSeconds(121))
                .build();
        OutboxEvent badUuid = OutboxEvent.builder()
                .tenantId(TENANT_A)
                .id(UUID.randomUUID())
                .topic("workorder.events.v1")
                .recordKey("bad-uuid")
                .payload("{\"eventId\":\"not-a-uuid\",\"eventType\":\"x.y\"}")
                .createdAt(WINDOW_START.plusSeconds(180))
                .publishedAt(WINDOW_START.plusSeconds(181))
                .build();
        when(repository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(good, badJson, badUuid));

        publisher.publishDueManifest();

        String message = capturedRecords(1).get(0).value();
        JsonNode manifest = objectMapper.readTree(message).path("payload");
        assertThat(manifest.path("eventCount").intValue()).isEqualTo(1);
    }

    /**
     * The records handed to Kafka, after checking each is a per-tenant manifest: the record header,
     * the envelope and the manifest payload all name the same tenant (ADR-0062 §3), so the
     * consumer's interceptor binds the tenant whose ledger the manifest is compared against.
     */
    @SuppressWarnings("unchecked")
    private List<ProducerRecord<String, String>> capturedRecords(int expected) {
        ArgumentCaptor<ProducerRecord<String, String>> records = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(expected)).send(records.capture());
        for (ProducerRecord<String, String> record : records.getAllValues()) {
            assertThat(record.topic()).isEqualTo("workorder.manifest.v1");
            JsonNode envelope = objectMapper.readTree(record.value());
            String manifestTenant = envelope.path("payload").path("tenantId").stringValue();
            assertThat(manifestTenant).isNotBlank();
            assertThat(TenantKafkaHeaders.read(record.headers())).contains(UUID.fromString(manifestTenant));
            assertThat(envelope.path("tenantId").stringValue()).isEqualTo(manifestTenant);
        }
        return records.getAllValues();
    }

    private Map<UUID, JsonNode> capturedManifestsByTenant(int expected) {
        return capturedRecords(expected).stream()
                .map(record -> objectMapper.readTree(record.value()).path("payload"))
                .collect(Collectors.toMap(
                        manifest -> UUID.fromString(manifest.path("tenantId").stringValue()), Function.identity()));
    }
}
