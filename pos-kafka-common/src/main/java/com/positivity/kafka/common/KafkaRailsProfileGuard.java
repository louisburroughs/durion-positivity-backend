package com.positivity.kafka.common;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Fails startup when a broker-less profile ({@code dev}, {@code test}, {@code pg}) is active
 * together with a deployed one ({@code alpha}, {@code prod}) and {@code local-kafka} is not.
 * {@link KafkaRails} would silently drop every Kafka bean in that combination, leaving a deployed
 * service that accepts traffic but never publishes or consumes a domain event.
 */
public class KafkaRailsProfileGuard {

    static final Set<String> BROKERLESS = Set.of("dev", "test", "pg");
    static final Set<String> DEPLOYED = Set.of("alpha", "prod");

    public KafkaRailsProfileGuard(Environment environment) {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (active.contains("local-kafka")) {
            return;
        }
        List<String> brokerless = active.stream().filter(BROKERLESS::contains).toList();
        List<String> deployed = active.stream().filter(DEPLOYED::contains).toList();
        if (!brokerless.isEmpty() && !deployed.isEmpty()) {
            throw new IllegalStateException("Broker-less profile(s) " + brokerless + " active together with deployed "
                    + "profile(s) " + deployed + ": @KafkaRails beans (ADR-0044 §4) would be silently dropped. "
                    + "Remove the broker-less profile, or add local-kafka to run against a local broker.");
        }
    }
}
