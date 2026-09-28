package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.config.OutboxEventWriter;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.accounting.BankStatementCommittedV1;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Queues the {@code accounting.bankstatement.committed} fact through the transactional outbox, in
 * the statement-commit transaction (SPEC §3.10, §4.4; story S2, #2301; ADR-0044 §4). A no-op when
 * the module's Kafka flag is off — the writer bean is conditional.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BankStatementFacts {

    static final String SOURCE_SERVICE = "pos-accounting";
    static final String ACCOUNTING_EVENTS_TOPIC = DomainTopics.events("accounting");

    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final Clock clock;

    /** Queues the fact; must run inside the commit transaction. */
    public void committed(@NonNull BankStatementCommittedV1 payload, @NonNull String actor) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        DomainEventEnvelope<BankStatementCommittedV1> envelope = DomainEventEnvelope.of(
                BankStatementCommittedV1.EVENT_TYPE,
                BankStatementCommittedV1.SCHEMA_VERSION,
                payload.statementId(),
                0L,
                SOURCE_SERVICE,
                null,
                actor,
                payload,
                clock);
        writer.publish(ACCOUNTING_EVENTS_TOPIC, envelope);
        log.debug("Queued {} statementId={}", BankStatementCommittedV1.EVENT_TYPE, payload.statementId());
    }
}
