package com.positivity.supplier.internal.service;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.supplier.SupplierInvoiceReceivedV1;
import com.positivity.domainevents.supplier.SupplierVendorUpdatedV1;
import com.positivity.supplier.internal.entity.SupplierOutboxEventEntity;
import com.positivity.supplier.internal.repository.SupplierOutboxEventRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * pos-supplier's reconciliation manifest of {@code supplier.events.v1} (CAP:550 S24, #2517; ADR-0044 §4, ADR-0062
 * §3): one manifest per active tenant per closed window, counting every fact the tenant published in it by eventId
 * timestamp, on {@code supplier.manifest.v1}, stamped with the tenant on the envelope and the record header.
 */
@DisplayName("pos-supplier ManifestPublisher — reconciliation manifest windows (S24, #2517)")
class ManifestPublisherTest {

    /** 12:10 UTC: the [11:00, 12:00) window closed more than the 5m grace ago. */
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:10:00Z"), ZoneOffset.UTC);

    private static final Instant WINDOW_START = Instant.parse("2026-10-08T11:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-10-08T12:00:00Z");
    private static final String EVENTS_TOPIC = "supplier.events.v1";
    private static final String MANIFEST_TOPIC = "supplier.manifest.v1";

    private final SupplierOutboxEventRepository repository = mock(SupplierOutboxEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final TenantRegistry tenantRegistry = mock(TenantRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger publisherLogger = (Logger) LoggerFactory.getLogger(ManifestPublisher.class);
    private SimpleMeterRegistry meterRegistry;
    private ManifestPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        when(tenantRegistry.activeTenantIds()).thenReturn(List.of(TENANT_A));
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meterRegistry);
        publisher =
                new ManifestPublisher(repository, kafkaTemplate, objectMapper, TEST_CLOCK, tenantRegistry, provider);
        ReflectionTestUtils.setField(publisher, "manifestTopic", MANIFEST_TOPIC);
        ReflectionTestUtils.setField(publisher, "window", Duration.ofHours(1));
        ReflectionTestUtils.setField(publisher, "grace", Duration.ofMinutes(5));
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 1000L);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        logs.start();
        publisherLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        publisherLogger.detachAppender(logs);
    }

    /** UUIDv7-shaped id whose embedded timestamp is {@code at}. */
    private static String eventIdAt(Instant at, int suffix) {
        long millis = at.toEpochMilli();
        return String.format("%08x-%04x-7000-8000-%012x", millis >>> 16, millis & 0xFFFF, suffix);
    }

    private static SupplierOutboxEventEntity row(UUID tenantId, String eventId, String eventType, Instant createdAt) {
        return SupplierOutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .topic(EVENTS_TOPIC)
                .recordKey(eventId)
                .eventType(eventType)
                .payload("{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType + "\",\"payload\":{}}")
                .createdAt(createdAt)
                .publishedAt(createdAt.plusSeconds(1))
                .build();
    }

    private void outboxReturns(List<SupplierOutboxEventEntity> rows) {
        when(repository.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(rows);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, JsonNode> manifestsByTenant(int expected) {
        ArgumentCaptor<ProducerRecord<String, String>> records = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(expected)).send(records.capture());
        for (ProducerRecord<String, String> record : records.getAllValues()) {
            assertThat(record.topic()).isEqualTo(MANIFEST_TOPIC);
            JsonNode envelope = objectMapper.readTree(record.value());
            String tenant = envelope.path("payload").path("tenantId").stringValue();
            assertThat(TenantKafkaHeaders.read(record.headers())).contains(UUID.fromString(tenant));
            assertThat(envelope.path("tenantId").stringValue()).isEqualTo(tenant);
            assertThat(envelope.path("sourceService").stringValue()).isEqualTo("pos-supplier");
            assertThat(envelope.path("eventType").stringValue())
                    .isEqualTo(ReconciliationManifestV1.eventTypeFor("supplier"));
        }
        return records.getAllValues().stream()
                .map(record -> objectMapper.readTree(record.value()).path("payload"))
                .collect(Collectors.toMap(
                        manifest -> UUID.fromString(manifest.path("tenantId").stringValue()), Function.identity()));
    }

    @Test
    @DisplayName("one manifest per tenant counts every supplier fact of the window, vendor and invoice alike")
    void perTenantManifestCountsEveryFact() {
        when(tenantRegistry.activeTenantIds()).thenReturn(List.of(TENANT_A, TENANT_B));
        String vendorFact = eventIdAt(WINDOW_START.plusSeconds(60), 1);
        String invoiceFact = eventIdAt(WINDOW_START.plusSeconds(120), 2);
        String otherTenant = eventIdAt(WINDOW_START.plusSeconds(180), 3);
        outboxReturns(List.of(
                row(TENANT_A, vendorFact, SupplierVendorUpdatedV1.EVENT_TYPE, WINDOW_START.plusSeconds(60)),
                row(TENANT_A, invoiceFact, SupplierInvoiceReceivedV1.EVENT_TYPE, WINDOW_START.plusSeconds(120)),
                row(TENANT_B, otherTenant, SupplierVendorUpdatedV1.EVENT_TYPE, WINDOW_START.plusSeconds(180))));

        publisher.publishDueManifest();

        verify(repository)
                .findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(
                        eq(EVENTS_TOPIC), eq(WINDOW_START.minusSeconds(1)), eq(WINDOW_END.plusSeconds(1)));
        Map<UUID, JsonNode> manifests = manifestsByTenant(2);
        JsonNode forA = manifests.get(TENANT_A);
        assertThat(forA.path("windowStartUtc").stringValue()).isEqualTo(WINDOW_START.toString());
        assertThat(forA.path("windowEndUtc").stringValue()).isEqualTo(WINDOW_END.toString());
        assertThat(forA.path("eventCount").longValue()).isEqualTo(2);
        assertThat(forA.path("eventIdsChecksum").stringValue())
                .isEqualTo(ReconciliationManifestV1.checksumOf(List.of(vendorFact, invoiceFact)));
        assertThat(forA.path("eventTypeCounts")
                        .path(SupplierVendorUpdatedV1.EVENT_TYPE)
                        .longValue())
                .isEqualTo(1);
        assertThat(manifests.get(TENANT_B).path("eventCount").longValue()).isEqualTo(1);
        assertThat(meterRegistry.get("supplier.manifest.published").counter().count())
                .isEqualTo(2d);
    }

    @Test
    @DisplayName("an active tenant that published nothing still gets a zero-count manifest")
    void quietTenantGetsZeroCountManifest() {
        when(tenantRegistry.activeTenantIds()).thenReturn(List.of(TENANT_A, TENANT_B));
        outboxReturns(List.of());

        publisher.publishDueManifest();

        Map<UUID, JsonNode> manifests = manifestsByTenant(2);
        assertThat(manifests.get(TENANT_B).path("eventCount").longValue()).isZero();
        assertThat(manifests.get(TENANT_B).path("eventIdsChecksum").stringValue())
                .isEqualTo(ReconciliationManifestV1.checksumOf(List.of()));
    }

    @Test
    @DisplayName("window membership is the eventId timestamp, not createdAt")
    void membershipIsTheEventIdTimestamp() {
        String justBefore = eventIdAt(WINDOW_START.minusMillis(1), 1);
        String inWindow = eventIdAt(WINDOW_START.plusSeconds(60), 2);
        String justAfter = eventIdAt(WINDOW_END, 3);
        outboxReturns(List.of(
                row(TENANT_A, justBefore, SupplierVendorUpdatedV1.EVENT_TYPE, WINDOW_START),
                row(TENANT_A, inWindow, SupplierVendorUpdatedV1.EVENT_TYPE, WINDOW_START.plusSeconds(60)),
                row(TENANT_A, justAfter, SupplierVendorUpdatedV1.EVENT_TYPE, WINDOW_END.minusMillis(1))));

        publisher.publishDueManifest();

        JsonNode forA = manifestsByTenant(1).get(TENANT_A);
        assertThat(forA.path("eventCount").longValue()).isEqualTo(1);
        assertThat(forA.path("eventIdsChecksum").stringValue())
                .isEqualTo(ReconciliationManifestV1.checksumOf(List.of(inWindow)));
    }

    @Test
    @DisplayName("an unreadable row is excluded and logged without its payload")
    void unreadableRowExcludedWithoutLoggingPayload() {
        String good = eventIdAt(WINDOW_START.plusSeconds(60), 1);
        SupplierOutboxEventEntity unreadable = row(
                TENANT_A, eventIdAt(WINDOW_START.plusSeconds(90), 2), SupplierVendorUpdatedV1.EVENT_TYPE, WINDOW_START);
        unreadable.setPayload("{\"eventId\":\"x\",\"payload\":{\"last4\":\"Z9Q8\"");
        outboxReturns(List.of(
                row(TENANT_A, good, SupplierVendorUpdatedV1.EVENT_TYPE, WINDOW_START.plusSeconds(60)), unreadable));

        publisher.publishDueManifest();

        assertThat(manifestsByTenant(1).get(TENANT_A).path("eventCount").longValue())
                .isEqualTo(1);
        assertThat(logs.list.stream()
                        .anyMatch(event ->
                                event.getFormattedMessage().contains("Z9Q8") || event.getThrowableProxy() != null))
                .as("no payload content and no exception message is logged")
                .isFalse();
    }

    @Test
    @DisplayName("a failed send leaves the window to be retried on the next run")
    void failedSendIsRetried() {
        outboxReturns(List.of());
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishDueManifest();
        publisher.publishDueManifest();

        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
        assertThat(meterRegistry
                        .get("supplier.manifest.publish.failures")
                        .counter()
                        .count())
                .isEqualTo(1d);
        assertThat(meterRegistry.get("supplier.manifest.published").counter().count())
                .isEqualTo(1d);
    }
}
