package com.positivity.customer.internal.config;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.entity.OutboxEvent;
import com.positivity.customer.internal.repository.OutboxEventRepository;
import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.tenancy.TenantRegistry;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

/**
 * Unit tests for the pos-customer {@link ManifestPublisher} (ADR-0044 §4, ADR-0062 §3).
 *
 * <p>
 * Each closed window gets one reconciliation manifest per tenant summarizing the
 * events that tenant published from the outbox in that window; consumers recompute
 * the same summary from that tenant's ledger rows and request a replay on drift.
 * The properties that matter:
 *
 * <ul>
 * <li>Window membership is decided by the <em>eventId</em> (UUIDv7) timestamp,
 * not by {@code createdAt}. The query pads its range by a second of slack, so a
 * row can be fetched and still belong to a neighbouring window; counting it in
 * the wrong one would manufacture drift on both sides.</li>
 * <li>Rows are grouped by the tenant the outbox row carries: one manifest per
 * tenant, each stamped with that tenant on the envelope and the record header,
 * on its own deterministic key.</li>
 * <li>Every active tenant of the registry gets a manifest each window, zero-count
 * when it published nothing, so consumers can alert on manifest absence per tenant
 * rather than treating silence as health.</li>
 * <li>A malformed row is skipped with a warning instead of failing the window
 * forever — one bad payload must not permanently block reconciliation.</li>
 * <li>A failed send leaves the window unadvanced so the next run retries it, and
 * catch-up is bounded per run so a long outage cannot monopolize a poll.</li>
 * </ul>
 */
@DisplayName("pos-customer ManifestPublisher — reconciliation manifest windows")
class ManifestPublisherTest {

    /** 12:10 UTC: the [11:00, 12:00) window closed more than the 5m grace ago. */
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-08T12:10:00Z"), ZoneOffset.UTC);

    private static final Instant WINDOW_START = Instant.parse("2026-07-08T11:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-07-08T12:00:00Z");
    private static final String EVENTS_TOPIC = "customer.events.v1";
    private static final String MANIFEST_TOPIC = "customer.manifest.v1";

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final TenantRegistry tenantRegistry = mock(TenantRegistry.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SimpleMeterRegistry meterRegistry;
    private ManifestPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        registryLists(TENANT_A);
        publisher = newPublisher(meterRegistry, TEST_CLOCK);
        brokerAcknowledges();
    }

    @SuppressWarnings("unchecked")
    private ManifestPublisher newPublisher(MeterRegistry registry, Clock clock) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        ManifestPublisher created =
                new ManifestPublisher(repository, kafkaTemplate, objectMapper, clock, tenantRegistry, provider);
        ReflectionTestUtils.setField(created, "eventsTopic", EVENTS_TOPIC);
        ReflectionTestUtils.setField(created, "manifestTopic", MANIFEST_TOPIC);
        ReflectionTestUtils.setField(created, "window", Duration.ofHours(1));
        ReflectionTestUtils.setField(created, "grace", Duration.ofMinutes(5));
        ReflectionTestUtils.setField(created, "sendTimeoutMs", 1000L);
        return created;
    }

    private void registryLists(UUID... tenants) {
        when(tenantRegistry.activeTenantIds()).thenReturn(List.of(tenants));
    }

    private void brokerAcknowledges() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    /** UUIDv7-shaped id whose embedded timestamp is {@code at}. */
    private static String eventIdAt(Instant at, int suffix) {
        long millis = at.toEpochMilli();
        return String.format("%08x-%04x-7000-8000-%012x", millis >>> 16, millis & 0xFFFF, suffix);
    }

    private static OutboxEvent row(String eventId, String eventType, Instant createdAt) {
        return row(TENANT_A, eventId, eventType, createdAt);
    }

    private static OutboxEvent row(UUID tenantId, String eventId, String eventType, Instant createdAt) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .topic(EVENTS_TOPIC)
                .recordKey(eventId)
                .payload("{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType + "\",\"payload\":{}}")
                .createdAt(createdAt)
                .publishedAt(createdAt.plusSeconds(1))
                .build();
    }

    private void outboxReturns(List<OutboxEvent> rows) {
        when(repository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(rows);
    }

    private JsonNode capturedManifest() {
        ProducerRecord<String, String> record = capturedRecords(1).get(0);
        assertThat(record.topic()).isEqualTo(MANIFEST_TOPIC);
        return objectMapper.readTree(record.value());
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
            JsonNode envelope = objectMapper.readTree(record.value());
            String manifestTenant = envelope.path("payload").path("tenantId").stringValue();
            assertThat(manifestTenant).isNotBlank();
            assertThat(TenantKafkaHeaders.read(record.headers())).contains(UUID.fromString(manifestTenant));
            assertThat(envelope.path("tenantId").stringValue()).isEqualTo(manifestTenant);
        }
        return records.getAllValues();
    }

    /** Captured manifest payloads keyed by tenant. */
    private Map<UUID, JsonNode> capturedManifestsByTenant(int expected) {
        return capturedRecords(expected).stream()
                .map(record -> objectMapper.readTree(record.value()).path("payload"))
                .collect(Collectors.toMap(
                        manifest -> UUID.fromString(manifest.path("tenantId").stringValue()), Function.identity()));
    }

    private double counter(String name) {
        var found = meterRegistry.find(name).counter();
        return found == null ? 0d : found.count();
    }

    @Test
    @DisplayName("publishes the latest closed window with its count, checksum, and per-type counts")
    void publishesManifestForClosedWindow() {
        String inWindow1 = eventIdAt(WINDOW_START.plusSeconds(60), 1);
        String inWindow2 = eventIdAt(WINDOW_START.plusSeconds(120), 2);
        outboxReturns(List.of(
                row(inWindow1, "SomethingChanged", WINDOW_START.plusSeconds(60)),
                row(inWindow2, "SomethingElseChanged", WINDOW_START.plusSeconds(120))));

        publisher.publishDueManifest();

        JsonNode envelope = capturedManifest();
        assertThat(envelope.path("sourceService").stringValue()).isEqualTo("pos-customer");
        assertThat(envelope.path("eventType").stringValue())
                .isEqualTo(ReconciliationManifestV1.eventTypeFor("customer"));
        assertThat(envelope.path("tenantId").stringValue()).isEqualTo(TENANT_A.toString());

        JsonNode manifest = envelope.path("payload");
        assertThat(manifest.path("tenantId").stringValue()).isEqualTo(TENANT_A.toString());
        assertThat(manifest.path("windowStartUtc").stringValue()).isEqualTo(WINDOW_START.toString());
        assertThat(manifest.path("windowEndUtc").stringValue()).isEqualTo(WINDOW_END.toString());
        assertThat(manifest.path("eventCount").longValue()).isEqualTo(2);
        assertThat(manifest.path("eventIdsChecksum").stringValue())
                .isEqualTo(ReconciliationManifestV1.checksumOf(List.of(inWindow1, inWindow2)));
        assertThat(manifest.path("eventTypeCounts").path("SomethingChanged").longValue())
                .isEqualTo(1);
        assertThat(manifest.path("eventTypeCounts").path("SomethingElseChanged").longValue())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("publishes one manifest per tenant found in the window, each summarizing only that tenant's rows")
    void publishesOneManifestPerTenantInWindow() {
        registryLists(TENANT_A, TENANT_B);
        String tenantA1 = eventIdAt(WINDOW_START.plusSeconds(60), 1);
        String tenantA2 = eventIdAt(WINDOW_START.plusSeconds(120), 2);
        String tenantB1 = eventIdAt(WINDOW_START.plusSeconds(180), 3);
        outboxReturns(List.of(
                row(TENANT_A, tenantA1, "SomethingChanged", WINDOW_START.plusSeconds(60)),
                row(TENANT_B, tenantB1, "SomethingElseChanged", WINDOW_START.plusSeconds(180)),
                row(TENANT_A, tenantA2, "SomethingChanged", WINDOW_START.plusSeconds(120))));

        publisher.publishDueManifest();

        // One tenant's rows must never leak into another tenant's count or checksum: a consumer
        // compares the manifest against that tenant's ledger rows only.
        Map<UUID, JsonNode> manifests = capturedManifestsByTenant(2);
        assertThat(manifests).containsOnlyKeys(TENANT_A, TENANT_B);
        JsonNode forA = manifests.get(TENANT_A);
        assertThat(forA.path("eventCount").longValue()).isEqualTo(2);
        assertThat(forA.path("eventIdsChecksum").stringValue())
                .isEqualTo(ReconciliationManifestV1.checksumOf(List.of(tenantA1, tenantA2)));
        assertThat(forA.path("eventTypeCounts").path("SomethingChanged").longValue())
                .isEqualTo(2);
        assertThat(forA.path("eventTypeCounts").has("SomethingElseChanged")).isFalse();
        JsonNode forB = manifests.get(TENANT_B);
        assertThat(forB.path("eventCount").longValue()).isEqualTo(1);
        assertThat(forB.path("eventIdsChecksum").stringValue())
                .isEqualTo(ReconciliationManifestV1.checksumOf(List.of(tenantB1)));
        assertThat(forB.path("eventTypeCounts").path("SomethingElseChanged").longValue())
                .isEqualTo(1);
        assertThat(forB.path("windowStartUtc").stringValue()).isEqualTo(WINDOW_START.toString());
        assertThat(counter("customer.manifest.published")).isEqualTo(2d);
    }

    @Test
    @DisplayName("keys the manifests of one window on distinct, deterministic per-tenant keys")
    void manifestRecordKeysDifferPerTenant() {
        registryLists(TENANT_A, TENANT_B);
        outboxReturns(List.of());

        publisher.publishDueManifest();

        List<String> keys = capturedRecords(2).stream().map(ProducerRecord::key).toList();
        assertThat(keys).doesNotHaveDuplicates();
        assertThat(keys.get(0)).isNotEqualTo(keys.get(1));
    }

    @Test
    @DisplayName("publishes a manifest for a tenant that appears in the window but not in the registry")
    void publishesForTenantOutsideTheRegistry() {
        registryLists(TENANT_A);
        String tenantB1 = eventIdAt(WINDOW_START.plusSeconds(60), 1);
        outboxReturns(List.of(row(TENANT_B, tenantB1, "SomethingChanged", WINDOW_START.plusSeconds(60))));

        publisher.publishDueManifest();

        // A stale registry must not hide a tenant that demonstrably published: its rows are the
        // ground truth. The registry tenant with no rows still gets its zero manifest.
        Map<UUID, JsonNode> manifests = capturedManifestsByTenant(2);
        assertThat(manifests.get(TENANT_B).path("eventCount").longValue()).isEqualTo(1);
        assertThat(manifests.get(TENANT_A).path("eventCount").longValue()).isZero();
    }

    @Test
    @DisplayName("excludes a row fetched by createdAt slack whose eventId falls outside the window")
    void excludesRowOutsideWindowByEventIdTimestamp() {
        String inWindow = eventIdAt(WINDOW_START.plusSeconds(60), 1);
        String justBefore = eventIdAt(WINDOW_START.minusMillis(1), 2);
        String justAfter = eventIdAt(WINDOW_END.plusMillis(1), 3);
        outboxReturns(List.of(
                row(justBefore, "SomethingChanged", WINDOW_START.minusMillis(1)),
                row(inWindow, "SomethingChanged", WINDOW_START.plusSeconds(60)),
                row(justAfter, "SomethingChanged", WINDOW_END.plusMillis(1))));

        publisher.publishDueManifest();

        // Membership is decided by the eventId timestamp, not the padded createdAt
        // query: counting a neighbour here would manufacture drift on both sides.
        JsonNode manifest = capturedManifest().path("payload");
        assertThat(manifest.path("eventCount").longValue()).isEqualTo(1);
        assertThat(manifest.path("eventIdsChecksum").stringValue())
                .isEqualTo(ReconciliationManifestV1.checksumOf(List.of(inWindow)));
    }

    @Test
    @DisplayName(
            "publishes a zero-count manifest per active tenant so consumers can alert on absence rather than silence")
    void publishesEmptyWindowManifestPerRegistryTenant() {
        registryLists(TENANT_A, TENANT_B);
        outboxReturns(List.of());

        publisher.publishDueManifest();

        Map<UUID, JsonNode> manifests = capturedManifestsByTenant(2);
        assertThat(manifests).containsOnlyKeys(TENANT_A, TENANT_B);
        for (JsonNode manifest : manifests.values()) {
            assertThat(manifest.path("eventCount").longValue()).isZero();
            assertThat(manifest.path("eventIdsChecksum").stringValue())
                    .isEqualTo(ReconciliationManifestV1.checksumOf(List.of()));
            assertThat(manifest.path("eventTypeCounts").isNull()).isTrue();
        }
    }

    @Test
    @DisplayName("publishes nothing for an empty window when the registry lists no tenant")
    void publishesNothingForEmptyWindowWithEmptyRegistry() {
        registryLists();
        outboxReturns(List.of());

        publisher.publishDueManifest();

        // There is no tenant to address a manifest to; the window still counts as published so
        // the scheduler moves on rather than retrying forever.
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        assertThat(counter("customer.manifest.published")).isZero();
        assertThat(counter("customer.manifest.publish.failures")).isZero();
    }

    @Test
    @DisplayName("counts a row with a blank event type under \"unknown\" rather than dropping it")
    void blankEventTypeCountedAsUnknown() {
        String eventId = eventIdAt(WINDOW_START.plusSeconds(60), 1);
        outboxReturns(List.of(row(eventId, "", WINDOW_START.plusSeconds(60))));

        publisher.publishDueManifest();

        JsonNode manifest = capturedManifest().path("payload");
        assertThat(manifest.path("eventCount").longValue()).isEqualTo(1);
        assertThat(manifest.path("eventTypeCounts").path("unknown").longValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("excludes a row with no eventId instead of failing the window")
    void skipsRowWithoutEventId() {
        OutboxEvent broken = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .tenantId(TENANT_A)
                .topic(EVENTS_TOPIC)
                .recordKey("k")
                .payload("{\"eventType\":\"PartyChanged\"}")
                .createdAt(WINDOW_START.plusSeconds(30))
                .publishedAt(WINDOW_START.plusSeconds(31))
                .build();
        outboxReturns(List.of(broken));

        publisher.publishDueManifest();

        assertThat(capturedManifest().path("payload").path("eventCount").longValue())
                .isZero();
    }

    @Test
    @DisplayName("excludes a row whose payload will not parse instead of blocking reconciliation forever")
    void skipsRowWithUnparsablePayload() {
        OutboxEvent broken = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .tenantId(TENANT_A)
                .topic(EVENTS_TOPIC)
                .recordKey("k")
                .payload("not json at all")
                .createdAt(WINDOW_START.plusSeconds(30))
                .publishedAt(WINDOW_START.plusSeconds(31))
                .build();
        outboxReturns(List.of(broken));

        publisher.publishDueManifest();

        assertThat(capturedManifest().path("payload").path("eventCount").longValue())
                .isZero();
    }

    @Test
    @DisplayName("does not re-publish a window it already published")
    void skipsAlreadyPublishedWindow() {
        outboxReturns(List.of());

        publisher.publishDueManifest();
        publisher.publishDueManifest();

        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("waits out the grace period, publishing the previous window until the latest one settles")
    void respectsGracePeriod() {
        ReflectionTestUtils.setField(publisher, "grace", Duration.ofMinutes(15));
        outboxReturns(List.of());

        publisher.publishDueManifest();

        // 12:10 with 15m grace: [11:00, 12:00) is not yet eligible, so the
        // published window is the previous one.
        assertThat(capturedManifest().path("payload").path("windowEndUtc").stringValue())
                .isEqualTo("2026-07-08T11:00:00Z");
    }

    @Test
    @DisplayName("retries the same window on the next run when the send fails")
    void retriesWindowAfterSendFailure() {
        outboxReturns(List.of());
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishDueManifest();
        publisher.publishDueManifest();

        // The window must not advance past a failure, or its manifest is lost.
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
        assertThat(counter("customer.manifest.publish.failures")).isEqualTo(1d);
        assertThat(counter("customer.manifest.published")).isEqualTo(1d);
    }

    @Test
    @DisplayName("retries the whole window when one tenant's send fails, re-sending the tenants already published")
    void retriesWholeWindowWhenOneTenantSendFails() {
        registryLists(TENANT_A, TENANT_B);
        outboxReturns(List.of());
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishDueManifest();
        publisher.publishDueManifest();

        // Re-publishing tenant A's manifest is harmless (the comparison is stateless); losing
        // tenant B's would leave that tenant blind for the window.
        List<ProducerRecord<String, String>> records = capturedRecords(4);
        assertThat(records)
                .extracting(record -> TenantKafkaHeaders.read(record.headers()).orElseThrow())
                .containsExactly(TENANT_A, TENANT_B, TENANT_A, TENANT_B);
        assertThat(counter("customer.manifest.publish.failures")).isEqualTo(1d);
        assertThat(counter("customer.manifest.published")).isEqualTo(3d);
    }

    @Test
    @DisplayName("counts a successful publish")
    void countsSuccessfulPublish() {
        outboxReturns(List.of());

        publisher.publishDueManifest();

        assertThat(counter("customer.manifest.published")).isEqualTo(1d);
        assertThat(counter("customer.manifest.publish.failures")).isZero();
    }

    @Test
    @DisplayName("publishes normally when no MeterRegistry is available")
    void publishesWithoutMeterRegistry() {
        ManifestPublisher withoutMetrics = newPublisher(null, TEST_CLOCK);
        outboxReturns(List.of());

        withoutMetrics.publishDueManifest();

        assertThat(capturedRecords(1).get(0).topic()).isEqualTo(MANIFEST_TOPIC);
    }

    @Test
    @DisplayName("publishes nothing when no window has closed yet")
    void publishesNothingBeforeTheFirstWindowCloses() {
        // Epoch + 30m with a 1h window: no aligned window boundary has passed.
        ManifestPublisher early = newPublisher(meterRegistry, Clock.fixed(Instant.ofEpochSecond(1800), ZoneOffset.UTC));

        early.publishDueManifest();

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("keys every manifest for a tenant and window deterministically, so re-publishes land on one partition")
    void manifestRecordKeyIsStablePerTenantAndWindow() {
        outboxReturns(List.of());

        publisher.publishDueManifest();
        // A second publisher instance, same window and tenant: the key must match.
        ManifestPublisher other = newPublisher(meterRegistry, TEST_CLOCK);
        other.publishDueManifest();

        List<String> keys = capturedRecords(2).stream().map(ProducerRecord::key).toList();
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).isEqualTo(keys.get(1));
    }

    @Test
    @DisplayName("after a gap, catches up window by window rather than skipping to the newest")
    void catchesUpWindowByWindow() {
        outboxReturns(List.of());

        // First run establishes [11:00, 12:00) as published.
        publisher.publishDueManifest();

        // Three hours later four windows have closed; the next run must publish the
        // intervening ones, not jump to the newest and leave holes.
        ManifestPublisher later = publisher;
        ReflectionTestUtils.setField(
                later, "clock", Clock.fixed(Instant.parse("2026-07-08T15:10:00Z"), ZoneOffset.UTC));
        later.publishDueManifest();

        List<String> values =
                capturedRecords(4).stream().map(ProducerRecord::value).toList();
        assertThat(values)
                .map(value -> objectMapper
                        .readTree(value)
                        .path("payload")
                        .path("windowEndUtc")
                        .stringValue())
                .containsExactly(
                        "2026-07-08T12:00:00Z", "2026-07-08T13:00:00Z", "2026-07-08T14:00:00Z", "2026-07-08T15:00:00Z");
    }
}
