package com.positivity.tax.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.tax.TaxRegistrationChangedV1;
import com.positivity.tax.TaxPostgresContainer;
import com.positivity.tax.internal.dto.TaxRegistrationCreateRequest;
import com.positivity.tax.internal.repository.OutboxEventRepository;
import com.positivity.tax.internal.service.TaxRegistrationService;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP:550 S32c AC 1 (fact): a registration change writes one outbox row in its transaction, the outbox publisher
 * puts {@code tax.registration.changed} on a real broker with the tenant header, and a manifest-driven replay of the
 * window re-sends the same event (same eventId, so consumers apply it once). Postgres on Testcontainers and a real
 * KRaft broker in the JVM (spring-kafka-test); the {@code pg} profile runs without the {@code @KafkaRails} beans, so the publisher is built here on the
 * container's broker. Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("pg")
@DisplayName("Tax registration outbox to Kafka (CAP:550 S32c)")
class TaxRegistrationOutboxKafkaIT {

    /** A real KRaft broker in this JVM; its listener is advertised on localhost. */
    private static final EmbeddedKafkaKraftBroker KAFKA = new EmbeddedKafkaKraftBroker(1, 1, "tax.events.v1");

    private static final String TOPIC = "tax.events.v1";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TaxPostgresContainer.registerDataSourceProperties(registry);
        KAFKA.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        KAFKA.destroy();
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
    void publishesAndReSends() {
        UUID tenant = UUID.randomUUID();
        UUID registrationId;
        TenantContext.bind(tenant);
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        UUID.randomUUID().toString(), null, List.of()));
        Instant before = Instant.now(clock);
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

        KafkaTemplate<String, String> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
        OutboxPublisher publisher = new OutboxPublisher(
                outboxEvents, template, clock, new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 30_000L);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "s32c-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class))) {
            consumer.subscribe(List.of(TOPIC));

            publisher.publishPending();
            List<ConsumerRecord<String, String>> first = await(consumer, registrationId.toString(), 1);

            ConsumerRecord<String, String> record = first.get(0);
            assertThat(TenantKafkaHeaders.read(record.headers())).contains(tenant);
            JsonNode envelope = objectMapper.readTree(record.value());
            assertThat(envelope.path("eventType").stringValue(null)).isEqualTo(TaxRegistrationChangedV1.EVENT_TYPE);
            TaxRegistrationChangedV1 fact =
                    objectMapper.treeToValue(envelope.path("payload"), TaxRegistrationChangedV1.class);
            assertThat(fact.registrationId()).isEqualTo(registrationId);
            assertThat(fact.registrationNumber()).isEqualTo("123456789RT0001");
            assertThat(fact.jurisdictionCode()).isEqualTo("CA");
            String eventId = envelope.path("eventId").stringValue(null);

            int queued = forTenant(tenant, () -> replay.replayBetween(before.minusSeconds(1), Instant.now(clock)));
            assertThat(queued).as("the window's one fact is re-queued").isEqualTo(1);
            publisher.publishPending();
            List<ConsumerRecord<String, String>> again = await(consumer, registrationId.toString(), 1);

            assertThat(objectMapper
                            .readTree(again.get(0).value())
                            .path("eventId")
                            .stringValue(null))
                    .as("the re-sent record is the same event, so a replica applies it once")
                    .isEqualTo(eventId);
        }
    }

    private static int forTenant(UUID tenant, java.util.function.IntSupplier work) {
        TenantContext.bind(tenant);
        try {
            return work.getAsInt();
        } finally {
            TenantContext.clear();
        }
    }

    private static List<ConsumerRecord<String, String>> await(
            KafkaConsumer<String, String> consumer, String key, int expected) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (found.size() < expected && System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (key.equals(record.key())) {
                    found.add(record);
                }
            }
        }
        assertThat(found).as("records keyed %s", key).hasSize(expected);
        return found;
    }
}
