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
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * #2657: pos-tax's Kafka rails against a real broker (Testcontainers {@code apache/kafka}, KRaft) on Postgres: an
 * outbox row is published to {@code tax.events.v1} with the tenant header; a {@code tax.outbox.replay-requested}
 * command produced to {@code tax.commands.v1} is read back from the broker and handed to the command listener, which
 * re-queues the window's fact; the publisher sends it again under the same eventId. The {@code pg} profile runs
 * without the {@code @KafkaRails} beans (they need a deployed profile), so the publisher and the listener are built
 * here on the broker the container provides, exactly as the rails beans would be wired. Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("pg")
@ResourceLock(TaxKafkaContainer.RESOURCE_LOCK)
@DisplayName("Outbox → tax.events.v1 → replay command → re-sent, on a real broker (#2657)")
class TaxOutboxBrokerIT {

    private static final String EVENTS_TOPIC = "tax.events.v1";
    private static final String COMMANDS_TOPIC = "tax.commands.v1";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TaxPostgresContainer.registerDataSourceProperties(registry);
        TaxKafkaContainer.registerKafkaProperties(registry);
    }

    @Autowired
    private OutboxEventWriter outboxWriter;

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Autowired
    private OutboxReplayService replayService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

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
            new org.springframework.transaction.support.TransactionTemplate(transactionManager)
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

        // The publisher, on a template bound to the container's broker, as the rails would build it.
        KafkaTemplate<String, String> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, TaxKafkaContainer.bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
        OutboxPublisher publisher = new OutboxPublisher(
                outboxEvents, template, clock, new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 30_000L);

        try (KafkaConsumer<String, String> events = consumer("it-events-" + eventId)) {
            events.subscribe(List.of(EVENTS_TOPIC));
            publisher.publishPending();

            ConsumerRecord<String, String> first = awaitRecord(events, eventId.toString());
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

            // A replay command goes through the commands topic and comes back off the broker to the listener.
            String command =
                    """
                    {"commandType":"tax.outbox.replay-requested","payload":{"since":"%s","until":"%s"}}
                    """.formatted(before.minusSeconds(1), Instant.now(clock).plusSeconds(1));
            template.send(TenantKafkaHeaders.record(COMMANDS_TOPIC, aggregate.toString(), command, tenant))
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
            TaxCommandListener listener = new TaxCommandListener(clock, objectMapper, replayService);
            try (KafkaConsumer<String, String> commands = consumer("it-commands-" + eventId)) {
                commands.subscribe(List.of(COMMANDS_TOPIC));
                ConsumerRecord<String, String> received = awaitAny(commands);
                assertThat(TenantKafkaHeaders.read(received.headers())).contains(tenant);
                TenantContext.bind(tenant);
                try {
                    listener.onCommand(received.value());
                } finally {
                    TenantContext.clear();
                }
            }
            OutboxEvent requeued = outboxEvents.findById(row.getId()).orElseThrow();
            assertThat(requeued.getPublishedAt())
                    .as("the window's fact is re-queued (its row marked unpublished again), same eventId")
                    .isNull();

            publisher.publishPending();
            ConsumerRecord<String, String> again = awaitRecord(events, eventId.toString());
            assertThat(again.offset()).isGreaterThan(first.offset());
            assertThat(objectMapper.readTree(again.value()).path("eventId").stringValue(null))
                    .isEqualTo(eventId.toString());
        } finally {
            template.destroy();
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
    private ConsumerRecord<String, String> awaitRecord(KafkaConsumer<String, String> consumer, String eventId)
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

    private static ConsumerRecord<String, String> awaitAny(KafkaConsumer<String, String> consumer) {
        Instant deadline = Instant.now().plusSeconds(60);
        List<ConsumerRecord<String, String>> seen = new ArrayList<>();
        while (Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofMillis(500)).forEach(seen::add);
            if (!seen.isEmpty()) {
                return seen.getLast();
            }
        }
        throw new AssertionError("No command record on the broker within 60 s");
    }
}
