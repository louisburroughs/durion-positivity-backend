package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.OutboxEventWriter;
import com.positivity.accounting.internal.entity.Deposit;
import com.positivity.accounting.internal.entity.DepositSession;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.accounting.DepositRecordedV1;
import java.time.Clock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Queues {@code accounting.deposit.recorded} (CAP:550 S18, #2514; ADR-0044 §3) on {@code accounting.events.v1} through
 * the outbox, in the transaction that recorded or reversed the deposit. The aggregate is the deposit and its version.
 */
@Slf4j
@Component
public class DepositFacts {

    static final String SOURCE_SERVICE = "pos-accounting";
    static final String ACCOUNTING_EVENTS_TOPIC = DomainTopics.events("accounting");

    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final Clock clock;

    public DepositFacts(ObjectProvider<OutboxEventWriter> outboxEventWriter, Clock clock) {
        this.outboxEventWriter = outboxEventWriter;
        this.clock = clock;
    }

    /** The fact of {@code deposit} as it stands, with the sessions it took. */
    public static @NonNull DepositRecordedV1 factOf(@NonNull Deposit deposit, @NonNull List<DepositSession> sessions) {
        return new DepositRecordedV1(
                deposit.getDepositId(),
                deposit.getBankGlAccountId(),
                deposit.getDepositDate(),
                deposit.getAmount(),
                deposit.getCurrencyCode(),
                sessions.stream().map(DepositSession::getSessionId).toList(),
                DepositRecordedV1.Status.valueOf(deposit.getStatus().name()),
                deposit.getJournalEntryId());
    }

    /** Queues the fact; must run inside the transaction that recorded or reversed the deposit, after its flush. */
    public void changed(@NonNull Deposit deposit, @NonNull List<DepositSession> sessions, @NonNull String actor) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        DepositRecordedV1 payload = factOf(deposit, sessions);
        writer.publish(
                ACCOUNTING_EVENTS_TOPIC,
                DomainEventEnvelope.of(
                        DepositRecordedV1.EVENT_TYPE,
                        DepositRecordedV1.SCHEMA_VERSION,
                        deposit.getDepositId(),
                        deposit.getVersion(),
                        SOURCE_SERVICE,
                        null,
                        actor,
                        payload,
                        clock));
        log.debug(
                "Queued {} depositId={} status={} amount={}",
                DepositRecordedV1.EVENT_TYPE,
                payload.depositId(),
                payload.status(),
                payload.amount());
    }
}
