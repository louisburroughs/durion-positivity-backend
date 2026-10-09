package com.positivity.tax.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.tax.TaxRegistrationChangedV1;
import com.positivity.tax.TaxPostgresContainer;
import com.positivity.tax.internal.dto.TaxRegistrationCreateRequest;
import com.positivity.tax.internal.repository.OutboxEventRepository;
import com.positivity.tax.internal.service.TaxRegistrationService;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP:550 S32c AC 1 (fact): a registration change writes one outbox row in its transaction on Postgres; the outbox
 * publisher sends it to {@code tax.events.v1} keyed by the registration with the tenant header; and a
 * manifest-driven replay of the window re-sends the same event (same eventId, so a replica applies it once). The
 * {@code pg} profile runs without the {@code @KafkaRails} beans, so the publisher is built here on a recording
 * {@code KafkaTemplate}. Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("pg")
@DisplayName("Tax registration outbox, publish and re-send on Postgres (CAP:550 S32c)")
class TaxRegistrationOutboxIT {

    private static final String TOPIC = "tax.events.v1";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TaxPostgresContainer.registerDataSourceProperties(registry);
    }

    @Autowired
    private TaxRegistrationService service;

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Autowired
    private OutboxReplayService replay;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("one change, one outbox row, one record on tax.events.v1; a replay re-sends the same event")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void publishesAndReSends() throws Exception {
        UUID tenant = UUID.randomUUID();
        Instant before = Instant.now(clock);
        TenantContext.bind(tenant);
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        UUID.randomUUID().toString(), null, List.of()));
        UUID registrationId;
        try {
            registrationId = service.create(new TaxRegistrationCreateRequest(
                            "CA",
                            "GST_HST",
                            "123456789 RT 0001",
                            LocalDate.of(2026, 1, 1),
                            null,
                            "Registered with the tax authority",
                            UUID.randomUUID()))
                    .registration()
                    .registrationId();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }

        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        OutboxPublisher publisher = new OutboxPublisher(
                outboxEvents, template, clock, new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 10_000L);

        publisher.publishPending();
        ProducerRecord<String, String> first =
                sentFor(template, registrationId, 1).get(0);

        assertThat(first.topic()).isEqualTo(TOPIC);
        assertThat(TenantKafkaHeaders.read(first.headers())).contains(tenant);
        JsonNode envelope = objectMapper.readTree(first.value());
        assertThat(envelope.path("eventType").stringValue(null)).isEqualTo(TaxRegistrationChangedV1.EVENT_TYPE);
        TaxRegistrationChangedV1 fact =
                objectMapper.treeToValue(envelope.path("payload"), TaxRegistrationChangedV1.class);
        assertThat(fact.registrationId()).isEqualTo(registrationId);
        assertThat(fact.registrationNumber()).isEqualTo("123456789RT0001");
        assertThat(fact.jurisdictionCode()).isEqualTo("CA");
        String eventId = envelope.path("eventId").stringValue(null);

        TenantContext.bind(tenant);
        int queued;
        try {
            queued = replay.replayBetween(
                    before.minusSeconds(1), Instant.now(clock).plusSeconds(1));
        } finally {
            TenantContext.clear();
        }
        assertThat(queued).as("the window's one fact is re-queued").isEqualTo(1);
        publisher.publishPending();

        List<ProducerRecord<String, String>> both = sentFor(template, registrationId, 2);
        assertThat(objectMapper.readTree(both.get(1).value()).path("eventId").stringValue(null))
                .as("the re-sent record is the same event, so a replica applies it once")
                .isEqualTo(eventId);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<ProducerRecord<String, String>> sentFor(
            KafkaTemplate<String, String> template, UUID registrationId, int expected) {
        ArgumentCaptor<ProducerRecord> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template, atLeastOnce()).send(sent.capture());
        List<ProducerRecord<String, String>> mine = sent.getAllValues().stream()
                .map(record -> (ProducerRecord<String, String>) record)
                .filter(record -> registrationId.toString().equals(record.key()))
                .toList();
        assertThat(mine).as("records keyed %s", registrationId).hasSize(expected);
        return mine;
    }
}
