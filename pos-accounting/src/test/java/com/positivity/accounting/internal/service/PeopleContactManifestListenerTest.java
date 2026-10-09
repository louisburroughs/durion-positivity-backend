package com.positivity.accounting.internal.service;

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
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
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
 * AP reads #2670 AC 9 (manifest): accounting's people-contact copy compares each {@code people-contact.manifest.v1}
 * window with its own {@code processed_events} rows (owner {@code people-contact}) and asks pos-people-contact to
 * replay a window that differs, for that tenant.
 */
@DisplayName("PeopleContactManifestListener — people-contact.events.v1 drift check and replay (#2670)")
class PeopleContactManifestListenerTest {

    private static final Instant WINDOW_START = Instant.parse("2026-10-09T11:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-10-09T12:00:00Z");
    private static final String COMMANDS_TOPIC = "people-contact.commands.v1";

    private final PeopleContactReplica replica = mock(PeopleContactReplica.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SimpleMeterRegistry meterRegistry;
    private PeopleContactManifestListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        meterRegistry = new SimpleMeterRegistry();
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meterRegistry);
        listener = new PeopleContactManifestListener(replica, kafkaTemplate, objectMapper, provider);
        ReflectionTestUtils.setField(listener, "peopleContactCommandsTopic", COMMANDS_TOPIC);
    }

    private String manifestFor(List<String> eventIds) {
        ReconciliationManifestV1 manifest = new ReconciliationManifestV1(
                TENANT_A,
                WINDOW_START,
                WINDOW_END,
                eventIds.size(),
                ReconciliationManifestV1.checksumOf(eventIds),
                null);
        return "{\"eventType\":\"people-contact.reconciliation.manifest\",\"payload\":"
                + objectMapper.writeValueAsString(manifest) + "}";
    }

    @Test
    @DisplayName("a window whose ledger matches requests nothing")
    void matchingWindow() {
        when(replica.receivedEventIds(any(), anyString(), anyString())).thenReturn(List.of("e-1", "e-2"));

        listener.onManifest(manifestFor(List.of("e-1", "e-2")));

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("AC 9: a manifest whose count differs from the ledger requests a replay for that tenant and window")
    @SuppressWarnings("unchecked")
    void driftRequestsReplay() {
        when(replica.receivedEventIds(any(), anyString(), anyString())).thenReturn(List.of("e-1"));

        listener.onManifest(manifestFor(List.of("e-1", "e-2")));

        verify(replica)
                .receivedEventIds(
                        eq(TENANT_A),
                        eq(UuidV7Timestamps.minStringAt(WINDOW_START)),
                        eq(UuidV7Timestamps.minStringAt(WINDOW_END)));
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(sent.capture());
        ProducerRecord<String, String> replay = sent.getValue();
        assertThat(replay.topic()).isEqualTo(COMMANDS_TOPIC);
        assertThat(TenantKafkaHeaders.read(replay.headers())).contains(TENANT_A);
        JsonNode command = objectMapper.readTree(replay.value());
        assertThat(command.path("commandType").stringValue()).isEqualTo("people-contact.outbox.replay-requested");
        assertThat(command.path("payload").path("since").stringValue()).isEqualTo(WINDOW_START.toString());
        assertThat(command.path("payload").path("until").stringValue()).isEqualTo(WINDOW_END.toString());
        assertThat(meterRegistry
                        .find("replica.drift")
                        .tag("owner", "people-contact")
                        .tag("tenant", TENANT_A.toString())
                        .counter()
                        .count())
                .isEqualTo(1d);
    }
}
