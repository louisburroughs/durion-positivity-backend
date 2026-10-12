package com.positivity.inventory.internal.service;

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

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for pos-inventory's {@link OrderManifestListener} (ADR-0044 §4, #2596): each of the module's two
 * {@code order.events.v1} consumers is reconciled against its own {@code processed_events} owner, in a consumer
 * group of its own, and drift requests a replay on {@code order.commands.v1}.
 */
@DisplayName("OrderManifestListener (pos-inventory) — order.events.v1 reconciliation per consumer")
class OrderManifestListenerTest {

    private static final Instant WINDOW_START = Instant.parse("2026-07-08T11:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-07-08T12:00:00Z");
    private static final String COMMANDS_TOPIC = "order.commands.v1";

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SimpleMeterRegistry meterRegistry;
    private OrderManifestListener listener;

    @BeforeEach
    void setUp() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        meterRegistry = new SimpleMeterRegistry();
        listener = newListener(meterRegistry);
    }

    @SuppressWarnings("unchecked")
    private OrderManifestListener newListener(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        OrderManifestListener created =
                new OrderManifestListener(processedEvents, kafkaTemplate, objectMapper, provider);
        ReflectionTestUtils.setField(created, "orderCommandsTopic", COMMANDS_TOPIC);
        return created;
    }

    /** The check for one owner, by the owner's processed_events tag. */
    private Consumer<String> checkFor(String owner) {
        return OrderEventsListener.OWNER.equals(owner)
                ? listener::onOrderEventsManifest
                : listener::onPurchaseOrderProjectionManifest;
    }

    private String manifestFor(List<String> eventIds) {
        ReconciliationManifestV1 manifest = new ReconciliationManifestV1(
                TENANT_A,
                WINDOW_START,
                WINDOW_END,
                eventIds.size(),
                ReconciliationManifestV1.checksumOf(eventIds),
                null);
        return "{\"eventType\":\"order.reconciliation.manifest\",\"payload\":"
                + objectMapper.writeValueAsString(manifest) + "}";
    }

    private void ownerHas(String owner, List<String> eventIds) {
        when(processedEvents.findEventIdsInRange(eq(owner), any(), anyString(), anyString()))
                .thenReturn(eventIds);
    }

    private double driftCount(String owner) {
        var counter = meterRegistry.find("replica.drift").tag("owner", owner).counter();
        return counter == null ? 0d : counter.count();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {OrderEventsListener.OWNER, PurchaseOrderProjectionListener.OWNER})
    @DisplayName("stays silent when the owner's ledger matches the manifest")
    void whenLedgerMatches_requestsNothing(String owner) {
        List<String> ids = List.of("id-1", "id-2");
        ownerHas(owner, ids);

        checkFor(owner).accept(manifestFor(ids));

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        assertThat(driftCount(owner)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {OrderEventsListener.OWNER, PurchaseOrderProjectionListener.OWNER})
    @DisplayName("requests one replay of the window, under the manifest's tenant, when the owner's ledger is short")
    void whenLedgerShort_requestsReplay(String owner) {
        ownerHas(owner, List.of("id-1"));

        checkFor(owner).accept(manifestFor(List.of("id-1", "id-2")));

        ProducerRecord<String, String> replay = capturedReplay();
        assertThat(replay.topic()).isEqualTo(COMMANDS_TOPIC);
        assertThat(replay.key()).isEqualTo(WINDOW_START.toString());
        JsonNode command = objectMapper.readTree(replay.value());
        assertThat(command.path("commandType").stringValue()).isEqualTo("order.outbox.replay-requested");
        assertThat(command.path("payload").path("since").stringValue()).isEqualTo(WINDOW_START.toString());
        assertThat(command.path("payload").path("until").stringValue()).isEqualTo(WINDOW_END.toString());
        assertThat(meterRegistry
                        .find("replica.drift")
                        .tag("owner", owner)
                        .tag("tenant", TENANT_A.toString())
                        .counter()
                        .count())
                .isEqualTo(1d);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {OrderEventsListener.OWNER, PurchaseOrderProjectionListener.OWNER})
    @DisplayName("scopes the lookup to its own owner, the manifest's tenant and its window")
    void lookupIsScopedToOwnerTenantAndWindow(String owner) {
        ownerHas(owner, List.of());

        checkFor(owner).accept(manifestFor(List.of()));

        verify(processedEvents)
                .findEventIdsInRange(
                        eq(owner),
                        eq(TENANT_A),
                        eq(UuidV7Timestamps.minStringAt(WINDOW_START)),
                        eq(UuidV7Timestamps.minStringAt(WINDOW_END)));
    }

    @Test
    @DisplayName("one consumer's drift never reads as the other's: each check compares its own rows")
    void eachOwnerIsCheckedOnItsOwn() {
        // The projection is short; the counter-sale consumer is whole.
        ownerHas(OrderEventsListener.OWNER, List.of("id-1", "id-2"));
        ownerHas(PurchaseOrderProjectionListener.OWNER, List.of("id-1"));
        String manifest = manifestFor(List.of("id-1", "id-2"));

        listener.onOrderEventsManifest(manifest);
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));

        listener.onPurchaseOrderProjectionManifest(manifest);
        capturedReplay();
        assertThat(driftCount(OrderEventsListener.OWNER)).isZero();
        assertThat(driftCount(PurchaseOrderProjectionListener.OWNER)).isEqualTo(1d);
    }

    @Test
    @DisplayName("each check reads the manifest topic in a consumer group of its own, so both see every manifest")
    void eachCheckHasItsOwnConsumerGroup() throws NoSuchMethodException {
        KafkaListener orderEvents = OrderManifestListener.class
                .getMethod("onOrderEventsManifest", String.class)
                .getAnnotation(KafkaListener.class);
        KafkaListener projection = OrderManifestListener.class
                .getMethod("onPurchaseOrderProjectionManifest", String.class)
                .getAnnotation(KafkaListener.class);

        assertThat(orderEvents.groupId()).isNotEqualTo(projection.groupId());
        assertThat(Arrays.asList(orderEvents.topics())).isEqualTo(Arrays.asList(projection.topics()));
        assertThat(orderEvents.topics()[0]).contains("order.manifest.v1");
        // Neither shares a group with the events consumers, whose records it must not split.
        assertThat(orderEvents.groupId()).contains("pos-inventory-order-manifests");
        assertThat(projection.groupId()).contains("pos-inventory-purchase-order-projection-manifests");
    }

    @Test
    @DisplayName("drops an unparseable manifest without querying or replaying")
    void unparseableManifest_isDropped() {
        listener.onOrderEventsManifest("not a manifest");
        listener.onPurchaseOrderProjectionManifest("not a manifest");

        verify(processedEvents, never()).findEventIdsInRange(anyString(), any(), anyString(), anyString());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("propagates a failed replay publish so the container redelivers the manifest (#2452)")
    void whenReplayPublishFails_propagates() {
        ownerHas(OrderEventsListener.OWNER, List.of());
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(new RuntimeException("broker down"));

        assertThatThrownBy(() -> listener.onOrderEventsManifest(manifestFor(List.of("id-1"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("broker down");
        assertThat(driftCount(OrderEventsListener.OWNER)).isEqualTo(1d);
    }

    @Test
    @DisplayName("detects drift and requests a replay when no MeterRegistry is available")
    void worksWithoutMeterRegistry() {
        OrderManifestListener withoutMetrics = newListener(null);
        ownerHas(PurchaseOrderProjectionListener.OWNER, List.of());

        withoutMetrics.onPurchaseOrderProjectionManifest(manifestFor(List.of("id-1")));

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, String> r) -> COMMANDS_TOPIC.equals(r.topic())));
    }

    @Test
    @DisplayName("skips a manifest without tenantId instead of reading it as any tenant's")
    void skipsAManifestWithoutTenant() {
        String legacy = """
                {"eventType":"order.reconciliation.manifest",
                 "payload":{"windowStartUtc":"%s","windowEndUtc":"%s","eventCount":2,
                   "eventIdsChecksum":"owner-checksum","eventTypeCounts":null}}
                """.formatted(WINDOW_START, WINDOW_END);

        listener.onOrderEventsManifest(legacy);
        listener.onPurchaseOrderProjectionManifest(legacy);

        verify(processedEvents, never()).findEventIdsInRange(any(), any(), any(), any());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        for (String owner : List.of(OrderEventsListener.OWNER, PurchaseOrderProjectionListener.OWNER)) {
            assertThat(meterRegistry
                            .find("replica.manifest.skipped")
                            .tag("owner", owner)
                            .tag("reason", "missing_tenant")
                            .counter()
                            .count())
                    .isEqualTo(1d);
        }
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
