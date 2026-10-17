package com.positivity.tax.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tax.TaxKafkaContainer;
import com.positivity.tax.TaxPostgresContainer;
import com.positivity.tax.internal.entity.OutboxEvent;
import com.positivity.tax.internal.repository.OutboxEventRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * #2657: pos-tax's Kafka rails against a real broker (Testcontainers {@code apache/kafka}, KRaft) on Postgres, with
 * the rails' own beans: {@code pg} plus {@code local-kafka} switches the {@code @KafkaRails} beans on, and the
 * {@code pg} profile's Kafka auto-configuration exclusion is lifted so the real {@code KafkaTemplate}, listener
 * container factory (tenant {@code RecordInterceptor}, dead-letter error handler) and {@link TaxCommandListener}
 * run against the broker. An outbox row is published by the real {@link OutboxPublisher} to {@code tax.events.v1}
 * with the tenant header; a {@code tax.outbox.replay-requested} command sent through the real template to
 * {@code tax.commands.v1} is consumed by the real {@code @KafkaListener}, which re-queues the window's fact (its row
 * becomes unpublished again); the publisher then sends it again under the same eventId. Hand-built consumers only
 * assert what reached the topics. The scheduled drains are parked (one-hour poll), so the test drives the publisher
 * itself. Requires Docker.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.autoconfigure.exclude=",
            "pos.tax.outbox.poll-interval-ms=3600000",
            "pos.tax.manifest.poll-interval-ms=3600000",
            "pos.tax.outbox.send-timeout-ms=30000"
        })
@ActiveProfiles({"pg", "local-kafka"})
@ResourceLock(TaxKafkaContainer.RESOURCE_LOCK)
@ResourceLock(TaxRegistrationOutboxIT.OUTBOX_LOCK)
@DisplayName("Outbox → tax.events.v1 → replay command → re-sent, on a real broker (#2657)")
class TaxOutboxBrokerIT {

    private static final String EVENTS_TOPIC = "tax.events.v1";
    private static final String COMMANDS_TOPIC = "tax.commands.v1";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        TaxPostgresContainer.registerDataSourceProperties(registry);
        TaxKafkaContainer.registerKafkaProperties(registry);
        TaxKafkaContainer.createTopics(EVENTS_TOPIC, COMMANDS_TOPIC);
    }

    @Autowired
    private OutboxEventWriter outboxWriter;

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private KafkaTemplate<String, String> template;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("publish, consume with the tenant header, replay through the commands topic, consume the re-send")
    void outboxRoundTripsThroughTheBroker() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID aggregate = UUID.randomUUID();
        UUID eventId = UUIDv7Generator.generate();
        Instant before = Instant.now(clock);

        // One fact on the outbox, stamped with the bound tenant.
        TenantContext.bind(tenant);
        try {
            // The writer joins the caller's transaction (MANDATORY), as a service method's would.
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(_ -> outboxWriter.publish(
                            EVENTS_TOPIC,
                            new DomainEventEnvelope<>(
                                    eventId,
                                    "tax.broker.it",
                                    1,
                                    aggregate,
                                    1L,
                                    Instant.now(clock),
                                    "pos-tax",
                                    null,
                                    null,
                                    "broker-it",
                                    Map.of("hello", "broker"))));
        } finally {
            TenantContext.clear();
        }

        try (KafkaConsumer<String, String> events = consumer("it-events-" + eventId);
                KafkaConsumer<String, String> commands = consumer("it-commands-" + eventId)) {
            events.subscribe(List.of(EVENTS_TOPIC));
            commands.subscribe(List.of(COMMANDS_TOPIC));
            publisher.publishPending();

            ConsumerRecord<String, String> first = awaitEvent(events, eventId.toString());
            assertThat(first.key()).isEqualTo(aggregate.toString());
            assertThat(TenantKafkaHeaders.read(first.headers())).contains(tenant);
            JsonNode envelope = objectMapper.readTree(first.value());
            assertThat(envelope.path("eventType").stringValue(null)).isEqualTo("tax.broker.it");
            assertThat(envelope.path("tenantId").stringValue(null)).isEqualTo(tenant.toString());
            OutboxEvent row = outboxEvents.findAll().stream()
                    .filter(e -> EVENTS_TOPIC.equals(e.getTopic())
                            && aggregate.toString().equals(e.getRecordKey()))
                    .findFirst()
                    .orElseThrow();
            assertThat(row.getPublishedAt())
                    .as("marked published only after the broker acknowledged")
                    .isNotNull();

            // A replay command goes through the real template; the real listener consumes it off the broker.
            String command =
                    """
                    {"commandType":"tax.outbox.replay-requested","payload":{"since":"%s","until":"%s"}}
                    """.formatted(before.minusSeconds(1), Instant.now(clock).plusSeconds(1));
            template.send(TenantKafkaHeaders.record(COMMANDS_TOPIC, aggregate.toString(), command, tenant))
                    .get(30, TimeUnit.SECONDS);
            ConsumerRecord<String, String> received = awaitCommand(commands, aggregate.toString(), tenant);
            assertThat(received.value()).contains("tax.outbox.replay-requested");

            Awaitility.await()
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(250))
                    .untilAsserted(() -> assertThat(
                                    outboxEvents.findById(row.getId()).orElseThrow().getPublishedAt())
                            .as("the listener re-queued the window's fact (row unpublished again), same eventId")
                            .isNull());

            publisher.publishPending();
            ConsumerRecord<String, String> again = awaitEvent(events, eventId.toString());
            assertThat(again.offset()).isGreaterThan(first.offset());
            assertThat(objectMapper.readTree(again.value()).path("eventId").stringValue(null))
                    .isEqualTo(eventId.toString());
        }
    }

    private static KafkaConsumer<String, String> consumer(String group) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                TaxKafkaContainer.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class));
    }

    /** The first record whose envelope carries {@code eventId}, within a minute; other tests' records are skipped. */
    private ConsumerRecord<String, String> awaitEvent(KafkaConsumer<String, String> consumer, String eventId)
            throws Exception {
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (eventId.equals(
                        objectMapper.readTree(record.value()).path("eventId").stringValue(null))) {
                    return record;
                }
            }
        }
        throw new AssertionError("No record with eventId " + eventId + " on the broker within 60 s");
    }

    /** The first command keyed {@code key} and stamped with {@code tenant}, within a minute; others are skipped. */
    private static ConsumerRecord<String, String> awaitCommand(
            KafkaConsumer<String, String> consumer, String key, UUID tenant) {
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (key.equals(record.key())
                        && TenantKafkaHeaders.read(record.headers()).filter(tenant::equals).isPresent()) {
                    return record;
                }
            }
        }
        throw new AssertionError("No command keyed " + key + " on the broker within 60 s");
    }
}
