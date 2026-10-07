package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.OutboxEventWriter;
import com.positivity.accounting.internal.entity.RegisterFloat;
import com.positivity.accounting.internal.entity.RegisterFloatChange;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.accounting.RegisterFloatChangedV1;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Queues {@code accounting.float.changed} (#2511; ADR-0044 §3) on {@code accounting.events.v1}
 * through the outbox, in the transaction that changed the float, and at each start for every
 * register. The aggregate is the register's float row and its version.
 */
@Slf4j
@Component
public class RegisterFloatFacts {

    static final String SOURCE_SERVICE = "pos-accounting";
    static final String ACCOUNTING_EVENTS_TOPIC = DomainTopics.events("accounting");

    /** Counts the facts queued (#2511 "Metrics"). */
    static final String PUBLISHED_COUNTER = "accounting.float.changed.published";

    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final Clock clock;
    private final @Nullable Counter published;

    public RegisterFloatFacts(
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.outboxEventWriter = outboxEventWriter;
        this.clock = clock;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.published = registry == null
                ? null
                : Counter.builder(PUBLISHED_COUNTER)
                        .description("accounting.float.changed facts queued")
                        .register(registry);
    }

    /** The fact of {@code change}, which left the float at its current amount. */
    public static @NonNull RegisterFloatChangedV1 factOf(
            @NonNull RegisterFloat registerFloat, @NonNull RegisterFloatChange change, @NonNull BigDecimal previous) {
        return new RegisterFloatChangedV1(
                registerFloat.getRegisterId(),
                registerFloat.getLocationId(),
                registerFloat.getAmount(),
                previous,
                RegisterFloatChangedV1.Kind.valueOf(change.getKind().name()),
                change.getEffectiveDate(),
                change.getJournalEntryId());
    }

    /** Queues the fact; must run inside the transaction that changed the float. */
    public void changed(
            @NonNull RegisterFloat registerFloat, @NonNull RegisterFloatChangedV1 payload, @NonNull String actor) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        writer.publish(
                ACCOUNTING_EVENTS_TOPIC,
                DomainEventEnvelope.of(
                        RegisterFloatChangedV1.EVENT_TYPE,
                        RegisterFloatChangedV1.SCHEMA_VERSION,
                        registerFloat.getRegisterFloatId(),
                        registerFloat.getVersion(),
                        SOURCE_SERVICE,
                        null,
                        actor,
                        payload,
                        clock));
        if (published != null) {
            published.increment();
        }
        log.debug(
                "Queued {} registerId={} amount={}",
                RegisterFloatChangedV1.EVENT_TYPE,
                payload.registerId(),
                payload.amount());
    }
}
