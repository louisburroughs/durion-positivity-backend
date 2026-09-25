package com.positivity.accounting.internal.config;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup loudly when {@code pos.accounting.kafka.enabled} is off in a deployed environment
 * (issue #2192; SPEC-inventory-adjustment-gl-posting.md §2.3 / §5.4, decision D3).
 *
 * <p>{@code application.yml} defaults the flag to {@code ${POS_ACCOUNTING_KAFKA_ENABLED:false}};
 * {@code application-alpha.yml} and {@code application-prod.yml} now set the literal {@code true}
 * so a deployment that forgets the environment variable no longer inherits a silent {@code false}.
 * Spring's relaxed binding still lets {@code POS_ACCOUNTING_KAFKA_ENABLED=false} in the process
 * environment override the profile value, so this guard reads the property back <em>after</em>
 * binding and refuses to start if it resolved to {@code false} however it got there. Without it,
 * {@code OutboxEventWriter} and {@code InventoryEventsListener} simply don't exist
 * ({@code @ConditionalOnProperty}), and a posting consumer that is silently absent posts nothing,
 * with no error and no health signal.
 *
 * <p>Scoped to {@code prod} and {@code alpha} only. {@code dev} runs H2 with the Kafka rails off by
 * design; every test suite in this module runs under {@code test}, {@code pg}, or a bespoke unit
 * profile that excludes Kafka autoconfiguration entirely (several {@code pg}-profile IT bases set
 * this property to {@code false} on purpose) — none of those are deployments this guard protects.
 *
 * <p>Retired by the Phase 0.4 tier-1 flip (ADR-0044 §4): once the {@code @ConditionalOnProperty}
 * opt-in is removed from the domain-flow beans, the flag and this guard both go with it.
 */
@Component
@Profile({"prod", "alpha"})
class KafkaEnabledGuard {

    static final String PROPERTY = "pos.accounting.kafka.enabled";

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
                            + ". pos-accounting's Kafka consumer and outbox rails must not be silently off in a"
                            + " deployed environment (issue #2192): every fact this module is meant to post to the"
                            + " GL would be dropped with no error. Set " + PROPERTY
                            + "=true (the profile default), or unset POS_ACCOUNTING_KAFKA_ENABLED / set it to true"
                            + " in the environment, to start.");
        }
    }
}
