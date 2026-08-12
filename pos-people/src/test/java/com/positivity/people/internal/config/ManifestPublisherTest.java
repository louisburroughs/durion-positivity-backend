package com.positivity.people.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.people.internal.entity.OutboxEvent;
import com.positivity.people.internal.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

/**
 * Unit tests for the pos-people {@link ManifestPublisher} (ADR-0044 §4).
 *
 * <p>
 * Each closed window gets one reconciliation manifest summarizing the events
 * published from the outbox in that window; consumers recompute the same summary
 * and request a replay on drift. The properties that matter:
 *
 * <ul>
 * <li>Window membership is decided by the <em>eventId</em> (UUIDv7) timestamp,
 * not by {@code createdAt}. The query pads its range by a second of slack, so a
 * row can be fetched and still belong to a neighbouring window; counting it in
 * the wrong one would manufacture drift on both sides.</li>
 * <li>Empty windows still publish, so consumers can alert on manifest absence
 * rather than treating silence as health.</li>
 * <li>A malformed row is skipped with a warning instead of failing the window
 * forever — one bad payload must not permanently block reconciliation.</li>
 * <li>A failed send leaves the window unadvanced so the next run retries it, and
 * catch-up is bounded per run so a long outage cannot monopolize a poll.</li>
 * </ul>
 */
@DisplayName("pos-people ManifestPublisher — reconciliation manifest windows")
class ManifestPublisherTest {

    /** 12:10 UTC: the [11:00, 12:00) window closed more than the 5m grace ago. */
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-08T12:10:00Z"), ZoneOffset.UTC);

    private static final Instant WINDOW_START = Instant.parse("2026-07-08T11:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-07-08T12:00:00Z");
    private static final String EVENTS_TOPIC = "people.events.v1";
    private static final String MANIFEST_TOPIC = "people.manifest.v1";

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SimpleMeterRegistry meterRegistry;
    private ManifestPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        publisher = newPublisher(meterRegistry, TEST_CLOCK);
        brokerAcknowledges();
    }

    @SuppressWarnings("unchecked")
    private ManifestPublisher newPublisher(MeterRegistry registry, Clock clock) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        ManifestPublisher created = new ManifestPublisher(repository, kafkaTemplate, objectMapper, clock, provider);
        ReflectionTestUtils.setField(created, "eventsTopic", EVENTS_TOPIC);
        ReflectionTestUtils.setField(created, "manifestTopic", MANIFEST_TOPIC);
        ReflectionTestUtils.setField(created, "window", Duration.ofHours(1));
        ReflectionTestUtils.setField(created, "grace", Duration.ofMinutes(5));
        ReflectionTestUtils.setField(created, "sendTimeoutMs", 1000L);
        return created;
    }

    private void brokerAcknowledges() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    /** UUIDv7-shaped id whose embedded timestamp is {@code at}. */
    private static String eventIdAt(Instant at, int suffix) {
        long millis = at.toEpochMilli();
        return String.format("%08x-%04x-7000-8000-%012x", millis >>> 16, millis & 0xFFFF, suffix);
    }

    private static OutboxEvent row(String eventId, String eventType, Instant createdAt) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
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
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(MANIFEST_TOPIC), anyString(), json.capture());
        return objectMapper.readTree(json.getValue());
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
        assertThat(envelope.path("sourceService").stringValue()).isEqualTo("pos-people");
        assertThat(envelope.path("eventType").stringValue()).isEqualTo(ReconciliationManifestV1.eventTypeFor("people"));

        JsonNode manifest = envelope.path("payload");
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
    @DisplayName("publishes a zero-count manifest so consumers can alert on absence rather than silence")
    void publishesEmptyWindowManifest() {
        outboxReturns(List.of());

        publisher.publishDueManifest();

        JsonNode manifest = capturedManifest().path("payload");
        assertThat(manifest.path("eventCount").longValue()).isZero();
        assertThat(manifest.path("eventIdsChecksum").stringValue())
                .isEqualTo(ReconciliationManifestV1.checksumOf(List.of()));
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

        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());
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
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishDueManifest();
        publisher.publishDueManifest();

        // The window must not advance past a failure, or its manifest is lost.
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), anyString());
        assertThat(counter("people.manifest.publish.failures")).isEqualTo(1d);
        assertThat(counter("people.manifest.published")).isEqualTo(1d);
    }

    @Test
    @DisplayName("counts a successful publish")
    void countsSuccessfulPublish() {
        outboxReturns(List.of());

        publisher.publishDueManifest();

        assertThat(counter("people.manifest.published")).isEqualTo(1d);
        assertThat(counter("people.manifest.publish.failures")).isZero();
    }

    @Test
    @DisplayName("publishes normally when no MeterRegistry is available")
    void publishesWithoutMeterRegistry() {
        ManifestPublisher withoutMetrics = newPublisher(null, TEST_CLOCK);
        outboxReturns(List.of());

        withoutMetrics.publishDueManifest();

        verify(kafkaTemplate).send(eq(MANIFEST_TOPIC), anyString(), anyString());
    }

    @Test
    @DisplayName("publishes nothing when no window has closed yet")
    void publishesNothingBeforeTheFirstWindowCloses() {
        // Epoch + 30m with a 1h window: no aligned window boundary has passed.
        ManifestPublisher early = newPublisher(meterRegistry, Clock.fixed(Instant.ofEpochSecond(1800), ZoneOffset.UTC));

        early.publishDueManifest();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("keys every manifest for a window deterministically, so re-publishes land on one partition")
    void manifestRecordKeyIsStablePerWindow() {
        outboxReturns(List.of());
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);

        publisher.publishDueManifest();
        // A second publisher instance, same window: the key must match.
        ManifestPublisher other = newPublisher(meterRegistry, TEST_CLOCK);
        other.publishDueManifest();

        verify(kafkaTemplate, times(2)).send(anyString(), key.capture(), anyString());
        assertThat(key.getAllValues()).hasSize(2);
        assertThat(key.getAllValues().get(0)).isEqualTo(key.getAllValues().get(1));
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

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(4)).send(anyString(), anyString(), json.capture());
        assertThat(json.getAllValues())
                .map(value -> objectMapper
                        .readTree(value)
                        .path("payload")
                        .path("windowEndUtc")
                        .stringValue())
                .containsExactly(
                        "2026-07-08T12:00:00Z", "2026-07-08T13:00:00Z", "2026-07-08T14:00:00Z", "2026-07-08T15:00:00Z");
    }
}
