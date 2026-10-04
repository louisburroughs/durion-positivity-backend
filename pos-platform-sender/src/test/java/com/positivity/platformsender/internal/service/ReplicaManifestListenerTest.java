package com.positivity.platformsender.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.platformsender.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.TenantHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ReplicaManifestListener — per-tenant drift detection and replay")
class ReplicaManifestListenerTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000002");
    private static final Instant START = Instant.parse("2026-10-03T12:00:00Z");
    private static final Instant END = Instant.parse("2026-10-03T12:05:00Z");
    private static final List<String> IDS =
            List.of("0199a8f0-0000-7000-8000-000000000001", "0199a8f0-0000-7000-8000-000000000002");

    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final ObjectMapper json = new ObjectMapper();
    private ReplicaManifestListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        listener = new ReplicaManifestListener(processed, kafka, json, mock(ObjectProvider.class));
        ReflectionTestUtils.setField(listener, "customerCommandsTopic", "customer.commands.v1");
        ReflectionTestUtils.setField(listener, "peopleContactCommandsTopic", "people-contact.commands.v1");
    }

    private String manifest(UUID tenantId, long count, String checksum) {
        return json.writeValueAsString(Map.of(
                "eventId",
                "0199a8f0-0000-7000-8000-0000000000ff",
                "payload",
                new ReconciliationManifestV1(tenantId, START, END, count, checksum, Map.of())));
    }

    @Test
    @DisplayName("a matching window requests nothing")
    void matchingWindow() {
        when(processed.findEventIdsInRange(eq("people-contact"), eq(TENANT), anyString(), anyString()))
                .thenReturn(IDS);

        listener.onPeopleContactManifest(manifest(TENANT, 2, ReconciliationManifestV1.checksumOf(IDS)));

        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("a drifted window asks the owner to replay it, under the manifest's tenant")
    @SuppressWarnings("unchecked")
    void driftRequestsReplay() {
        when(processed.findEventIdsInRange(eq("customer"), eq(TENANT), anyString(), anyString()))
                .thenReturn(IDS.subList(0, 1));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        listener.onCustomerManifest(manifest(TENANT, 2, ReconciliationManifestV1.checksumOf(IDS)));

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.captor();
        verify(kafka).send(sent.capture());
        ProducerRecord<String, String> command = sent.getValue();
        assertThat(command.topic()).isEqualTo("customer.commands.v1");
        assertThat(command.value()).contains("\"commandType\":\"customer.outbox.replay-requested\"");
        assertThat(command.value()).contains(START.toString()).contains(END.toString());
        assertThat(new String(
                        command.headers()
                                .lastHeader(TenantHeaders.KAFKA_TENANT_ID)
                                .value(),
                        StandardCharsets.UTF_8))
                .isEqualTo(TENANT.toString());
    }

    @Test
    @DisplayName("a manifest without a tenant, or unparsable, is skipped")
    void skipped() {
        listener.onPeopleContactManifest(manifest(null, 2, "x"));
        listener.onPeopleContactManifest("not json");

        verify(processed, never()).findEventIdsInRange(any(), any(), any(), any());
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("a replay request that fails to send propagates for container redelivery (#2452)")
    void failedSendPropagates() {
        when(processed.findEventIdsInRange(eq("customer"), eq(TENANT), anyString(), anyString()))
                .thenReturn(IDS.subList(0, 1));
        when(kafka.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("broker down"));

        assertThatThrownBy(() -> listener.onCustomerManifest(manifest(TENANT, 2, "owner-checksum")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broker down");
    }

    @Test
    @DisplayName("a replay request the broker rejects propagates for container redelivery (#2452)")
    void brokerRejectionPropagates() {
        when(processed.findEventIdsInRange(eq("people-contact"), eq(TENANT), anyString(), anyString()))
                .thenReturn(IDS.subList(0, 1));
        when(kafka.send(any(ProducerRecord.class)))
                .thenAnswer(invocation -> CompletableFuture.failedFuture(new KafkaException("not leader")));

        assertThatThrownBy(() -> listener.onPeopleContactManifest(manifest(TENANT, 2, "owner-checksum")))
                .isInstanceOf(KafkaException.class)
                .hasRootCauseMessage("not leader");
    }
}
