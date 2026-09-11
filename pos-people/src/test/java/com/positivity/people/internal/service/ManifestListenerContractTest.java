package com.positivity.people.internal.service;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.people.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * The two consumer-side reconciliation listeners in this module — one per upstream
 * owner — are structurally identical, so their shared contract is pinned once here
 * (ADR-0044 §4, issues #875 and #892).
 *
 * <p>
 * Reconciliation is the safety net under at-least-once delivery: if a fact was
 * lost, nothing else will notice. The properties that make it safe to run
 * automatically are:
 *
 * <ul>
 * <li><b>The verdict is stateless.</b> The same manifest reprocessed — a
 * redelivery, or the owner re-publishing after a restart — yields the same
 * answer, so a duplicate replay request costs one idempotent re-delivery and
 * never corruption.</li>
 * <li><b>Window membership is the UUIDv7 timestamp inside each recorded
 * eventId</b>, which is exactly the definition the owner used to build the
 * manifest. Any other definition would make the two counts disagree for reasons
 * that have nothing to do with drift.</li>
 * <li><b>Drift both increments the metric and requests a replay.</b> Metric
 * without replay means silent divergence; replay without metric means nobody
 * learns it is happening.</li>
 * <li><b>A malformed manifest is dropped, not retried</b>, because the next
 * window repeats the check anyway — retrying a manifest that cannot be parsed
 * would wedge the partition for no gain.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("reconciliation manifest listeners — shared drift-detection contract")
class ManifestListenerContractTest {

    private static final Instant WINDOW_START = Instant.parse("2026-08-11T09:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-08-11T09:05:00Z");

    static final List<String> LISTENERS = List.of("people-contact", "location");

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);
    }

    /** How to hand a message to one listener, and the owner/topic it is wired to. */
    private record Listener(String owner, String commandsTopic, Consumer<String> dispatch) {}

    private Listener listener(String owner) {
        if ("people-contact".equals(owner)) {
            PeopleContactManifestListener listener = new PeopleContactManifestListener(
                    processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
            ReflectionTestUtils.setField(listener, "peopleContactCommandsTopic", "people-contact.commands.v1");
            return new Listener(PeopleContactEventsListener.OWNER, "people-contact.commands.v1", listener::onManifest);
        }
        LocationManifestListener listener = new LocationManifestListener(
                processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
        ReflectionTestUtils.setField(listener, "locationCommandsTopic", "location.commands.v1");
        return new Listener(LocationEventsListener.OWNER, "location.commands.v1", listener::onManifest);
    }

    private String manifest(long eventCount, String checksum) {
        return """
                {"eventId":"evt-1","eventType":"x.reconciliation.manifest",
                 "payload":{"tenantId":"%s","windowStartUtc":"%s","windowEndUtc":"%s","eventCount":%d,
                   "eventIdsChecksum":"%s","eventTypeCounts":{"x.updated":%d}}}
                """.formatted(TENANT_A, WINDOW_START, WINDOW_END, eventCount, checksum, eventCount);
    }

    private void replicaHolds(Listener listener, List<String> eventIds) {
        when(processedEventRepository.findEventIdsInRange(
                        listener.owner(),
                        TENANT_A,
                        UuidV7Timestamps.minStringAt(WINDOW_START),
                        UuidV7Timestamps.minStringAt(WINDOW_END)))
                .thenReturn(eventIds);
    }

    private double driftCount(String owner) {
        return meterRegistry.find("replica.drift").tag("owner", owner).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("stays silent when the local replica matches the owner's manifest")
    void matchingWindowRequestsNothing(String owner) {
        Listener listener = listener(owner);
        List<String> ids = List.of("019ff000-0000-7000-8000-000000000001");
        replicaHolds(listener, ids);

        listener.dispatch().accept(manifest(ids.size(), ReconciliationManifestV1.checksumOf(ids)));

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        assertThat(driftCount(listener.owner())).isZero();
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("counts drift and requests a replay for the same window when counts disagree")
    void missingEventsTriggerReplay(String owner) {
        Listener listener = listener(owner);
        // The owner says two facts; this replica recorded one.
        replicaHolds(listener, List.of("019ff000-0000-7000-8000-000000000001"));

        listener.dispatch().accept(manifest(2, "whatever-the-owner-computed"));

        assertThat(driftCount(listener.owner())).isEqualTo(1.0);
        ProducerRecord<String, String> replay = capturedReplay();
        assertThat(replay.topic()).isEqualTo(listener.commandsTopic());
        assertThat(replay.key()).isEqualTo(WINDOW_START.toString());
        // The replay must name the window that failed, or the repair fixes the wrong range.
        assertThat(objectMapper
                        .readTree(replay.value())
                        .path("payload")
                        .path("since")
                        .asString())
                .isEqualTo(WINDOW_START.toString());
        assertThat(objectMapper
                        .readTree(replay.value())
                        .path("payload")
                        .path("until")
                        .asString())
                .isEqualTo(WINDOW_END.toString());
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("treats a matching count with a different checksum as drift")
    void checksumMismatchIsDrift(String owner) {
        Listener listener = listener(owner);
        List<String> ids = List.of("019ff000-0000-7000-8000-000000000001");
        replicaHolds(listener, ids);

        // Same number of events, different identities — the count alone would have passed.
        listener.dispatch().accept(manifest(ids.size(), "a-different-checksum"));

        assertThat(driftCount(listener.owner())).isEqualTo(1.0);
        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("reaches the same verdict every time the same manifest is reprocessed")
    void verdictIsStateless(String owner) {
        Listener listener = listener(owner);
        replicaHolds(listener, List.of("019ff000-0000-7000-8000-000000000001"));
        String message = manifest(2, "whatever-the-owner-computed");

        listener.dispatch().accept(message);
        listener.dispatch().accept(message);

        // A redelivered manifest costs one more idempotent replay, never a different answer.
        assertThat(driftCount(listener.owner())).isEqualTo(2.0);
        verify(kafkaTemplate, org.mockito.Mockito.times(2)).send(any(ProducerRecord.class));
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("drops an unparseable manifest rather than retrying it")
    void unparseableManifestIsDropped(String owner) {
        Listener listener = listener(owner);

        listener.dispatch().accept("{not json");

        // The next window repeats the check, so retrying here would wedge the partition for nothing.
        verify(processedEventRepository, never()).findEventIdsInRange(anyString(), any(), anyString(), anyString());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("still counts drift when the replay request cannot be published")
    void replayPublishFailureStillCountsDrift(String owner) {
        Listener listener = listener(owner);
        replicaHolds(listener, List.of());
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("broker down"));

        listener.dispatch().accept(manifest(3, "owner-checksum"));

        // Best effort by design: the metric already fired and the next manifest re-detects, so a
        // broker outage must not take the consumer down with it.
        assertThat(driftCount(listener.owner())).isEqualTo(1.0);
    }

    @Test
    @DisplayName("works with no MeterRegistry present, since the metric is optional")
    void absentMeterRegistryIsTolerated() {
        when(meterRegistryProvider.getIfAvailable()).thenReturn(null);
        PeopleContactManifestListener listener = new PeopleContactManifestListener(
                processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
        ReflectionTestUtils.setField(listener, "peopleContactCommandsTopic", "people-contact.commands.v1");
        when(processedEventRepository.findEventIdsInRange(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of());

        listener.onManifest(manifest(3, "owner-checksum"));

        // The replay still goes out; only the counter is skipped.
        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }

    /** The one replay command handed to Kafka: it must ride the manifest's tenant header (ADR-0062 §3). */
    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> capturedReplay() {
        ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(record.capture());
        assertThat(TenantKafkaHeaders.read(record.getValue().headers())).contains(TENANT_A);
        return record.getValue();
    }

    /** Matches a replay command routed to {@code topic} under the manifest's tenant header. */
    private static ProducerRecord<String, String> replayOn(String topic) {
        return org.mockito.ArgumentMatchers.argThat(
                (ProducerRecord<String, String> record) -> topic.equals(record.topic())
                        && TenantKafkaHeaders.read(record.headers())
                                .filter(TENANT_A::equals)
                                .isPresent());
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("skips a manifest without tenantId instead of reading it as any tenant's (WS4-3)")
    void skipsAManifestWithoutTenant(String owner) {
        Listener listener = listener(owner);
        // Published before manifests were per tenant (ADR-0062 WS4-3): it summarised every
        // tenant's rows at once, so no single tenant's ledger can be compared against it.
        String legacy = """
                {"eventType":"x.reconciliation.manifest",
                 "payload":{"windowStartUtc":"%s","windowEndUtc":"%s","eventCount":2,
                   "eventIdsChecksum":"owner-checksum","eventTypeCounts":null}}
                """.formatted(WINDOW_START, WINDOW_END);

        listener.dispatch().accept(legacy);

        verify(processedEventRepository, never()).findEventIdsInRange(any(), any(), any(), any());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        assertThat(meterRegistry.find("replica.drift").counters()).isEmpty();
        var skipped = meterRegistry
                .find("replica.manifest.skipped")
                .tag("reason", "missing_tenant")
                .counter();
        assertThat(skipped).isNotNull();
        assertThat(skipped.count()).isEqualTo(1.0);
    }
}
