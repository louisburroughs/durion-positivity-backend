package com.positivity.inventory.internal.config;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup loudly when {@code pos.inventory.kafka.enabled} is off in a deployed environment
 * (issue #2192; SPEC-inventory-adjustment-gl-posting.md §2.3 / §5.4, decision D3).
 *
 * <p>{@code application.yml} defaults the flag to {@code ${POS_INVENTORY_KAFKA_ENABLED:false}};
 * {@code application-alpha.yml} and {@code application-prod.yml} now set the literal {@code true}
 * so a deployment that forgets the environment variable no longer inherits a silent {@code false}.
 * Spring's relaxed binding still lets {@code POS_INVENTORY_KAFKA_ENABLED=false} in the process
 * environment override the profile value, so this guard reads the property back <em>after</em>
 * binding and refuses to start if it resolved to {@code false} however it got there. Without it,
 * {@code OutboxEventWriter} simply doesn't exist ({@code @ConditionalOnProperty}), and every
 * queued fact (including {@code InventoryAdjustedV1} and {@code ScrapPostedV1}) is silently
 * dropped, with no error and no health signal.
 *
 * <p>Scoped to {@code prod} and {@code alpha} only. {@code dev} runs H2 with the Kafka rails off by
 * design; every test suite in this module runs under {@code test}, {@code pg}, or a bespoke unit
 * profile that excludes Kafka autoconfiguration entirely (the {@code pg}-profile IT base sets this
 * property to {@code false} on purpose) — none of those are deployments this guard protects.
 *
 * <p>Retired by the Phase 0.4 tier-1 flip (ADR-0044 §4): once the {@code @ConditionalOnProperty}
 * opt-in is removed from the domain-flow beans, the flag and this guard both go with it.
 */
@Component
@Profile({"prod", "alpha"})
class KafkaEnabledGuard {

    static final String PROPERTY = "pos.inventory.kafka.enabled";

    private final Environment environment;

    KafkaEnabledGuard(@NonNull Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void verifyKafkaEnabled() {
        boolean enabled = environment.getProperty(PROPERTY, Boolean.class, Boolean.FALSE);
        if (!enabled) {
            throw new IllegalStateException(
                    PROPERTY + " is false in profile(s) " + Arrays.toString(environment.getActiveProfiles())
                            + ". pos-inventory's Kafka outbox rails must not be silently off in a deployed"
                            + " environment (issue #2192): every fact this module publishes would be dropped with"
                            + " no error. Set " + PROPERTY
                            + "=true (the profile default), or unset POS_INVENTORY_KAFKA_ENABLED / set it to true"
                            + " in the environment, to start.");
        }
    }
}
