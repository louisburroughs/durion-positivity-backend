package com.positivity.tax;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.common.errors.TopicExistsException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;

/**
 * One Kafka broker for the test JVM (#2657), started lazily from {@link #registerKafkaProperties}. Requires Docker.
 * {@code TaxOutboxBrokerIT} points the real {@code @KafkaRails} beans at it ({@code spring.kafka.bootstrap-servers}),
 * and builds its assertion consumers on {@link #bootstrapServers()}.
 *
 * <p>Testcontainers' {@link KafkaContainer} (the {@code apache/kafka} image, KRaft, no ZooKeeper) advertises the
 * mapped host port itself, which is what the earlier hand-rolled {@code GenericContainer} attempts got wrong: the
 * broker advertised {@code 0.0.0.0} (its in-container listener) and every client connect after the bootstrap
 * handshake failed. The container's bootstrap address is what {@code spring.kafka.bootstrap-servers} and every
 * producer or consumer built by a test must use.
 */
public final class TaxKafkaContainer {

    /** JUnit resource lock name for tests that share this broker and must not race on a topic. */
    public static final String RESOURCE_LOCK = "pos-tax-kafka";

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    private TaxKafkaContainer() {}

    /** Points {@code spring.kafka.bootstrap-servers} at the broker. */
    public static void registerKafkaProperties(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /** The broker's bootstrap address as the host sees it, for a producer or consumer a test builds itself. */
    public static String bootstrapServers() {
        start();
        return KAFKA.getBootstrapServers();
    }

    /**
     * Creates the topics up front, so a listener subscribing at context start finds them (a topic created later is
     * only seen at the consumer's next metadata refresh, minutes away).
     */
    public static void createTopics(String... topics) throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()))) {
            admin.createTopics(Arrays.stream(topics)
                            .map(topic -> new NewTopic(topic, 1, (short) 1))
                            .toList())
                    .all()
                    .get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw e;
            }
        }
    }

    private static synchronized void start() {
        if (!KAFKA.isRunning()) {
            KAFKA.start();
        }
    }
}
