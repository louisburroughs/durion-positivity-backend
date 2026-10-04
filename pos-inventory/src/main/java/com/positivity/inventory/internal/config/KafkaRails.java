package com.positivity.inventory.internal.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Profile;

/**
 * Marks a pos-inventory bean that belongs to the Kafka rails — a domain-event listener, the
 * transactional outbox writer/publisher, a command or manifest publisher, or the listener
 * infrastructure itself.
 *
 * <p>ADR-0044 §4 makes Kafka tier-1 infrastructure: these beans are always present in every
 * deployed profile ({@code docker}, {@code alpha}, {@code prod}, ...), with no
 * {@code @ConditionalOnProperty} opt-in (the Phase 0.4 tier-1 flip, issue #2195, which retired
 * {@code pos.inventory.kafka.enabled}). The only profiles that run without them are the ones that run
 * without a broker: {@code dev} (local JVM on H2) and the test profiles {@code test} and
 * {@code pg}. {@code local-kafka} puts them back on for a developer running against a local
 * broker.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Profile(KafkaRails.PROFILES)
public @interface KafkaRails {

    /** Active everywhere except the broker-less {@code dev} and test profiles. */
    String PROFILES = "local-kafka | !(dev | test | pg)";
}
