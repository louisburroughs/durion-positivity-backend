package com.positivity.kafka.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Profile;

/**
 * Marks a bean that belongs to a module's Kafka rails: a domain-event listener, the transactional
 * outbox writer/publisher, a command or manifest publisher, or the listener infrastructure itself.
 *
 * <p>ADR-0044 §4 makes Kafka tier-1 infrastructure: these beans are always present in every
 * deployed profile ({@code docker}, {@code alpha}, {@code prod}, ...), with no
 * {@code @ConditionalOnProperty} opt-in such as {@code pos.<module>.kafka.enabled} (issues #2195,
 * #2463). The only profiles that run without them are the ones that run without a broker:
 * {@code dev} (local JVM on H2) and the test profiles {@code test} and {@code pg}.
 * {@code local-kafka} puts them back on for a developer running against a local broker.
 *
 * <p>The expression {@value #PROFILES} reads: on when {@code local-kafka} is active, or when none
 * of {@code dev}, {@code test}, {@code pg} is. The one way it can silently drop the rails in a
 * deployment is a broker-less profile combined with a deployed one (for example
 * {@code dev,alpha}); {@link KafkaRailsProfileGuard} fails startup in that case.
 *
 * <p>Anything that injects a rails bean (an outbox writer, say) must tolerate its absence in the
 * broker-less profiles: inject through {@code ObjectProvider}/{@code Optional}, or carry
 * {@code @KafkaRails} itself.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Profile(KafkaRails.PROFILES)
public @interface KafkaRails {

    /** Active everywhere except the broker-less {@code dev} and test profiles. */
    String PROFILES = "local-kafka | !(dev | test | pg)";
}
