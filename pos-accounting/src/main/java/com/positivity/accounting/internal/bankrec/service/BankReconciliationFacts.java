package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.enums.InvalidationReason;
import com.positivity.accounting.internal.config.OutboxEventWriter;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.accounting.BankReconciliationApprovedV1;
import com.positivity.domainevents.accounting.BankReconciliationCancelledV1;
import com.positivity.domainevents.accounting.BankReconciliationInvalidatedV1;
import com.positivity.domainevents.accounting.BankReconciliationSubmittedV1;
import com.positivity.domainevents.accounting.BankReconciliationSupersededV1;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Queues the {@code accounting.bankreconciliation.*} facts through the transactional outbox, in the
 * transaction that changes the reconciliation (SPEC §3.10; story S5, #2304; ADR-0044 §4). Every fact is
 * keyed by the reconciliation id, {@code schemaVersion} 1, on {@code accounting.events.v1}. A no-op when
 * the module's Kafka flag is off — the writer bean is conditional.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BankReconciliationFacts {

    static final String SOURCE_SERVICE = "pos-accounting";
    static final String ACCOUNTING_EVENTS_TOPIC = DomainTopics.events("accounting");

    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final Clock clock;

    /** {@code .submitted}: the reconciliation passed E4 and awaits approval. */
    public void submitted(@NonNull BankReconciliation recon, @NonNull String actor) {
        publish(
                BankReconciliationSubmittedV1.EVENT_TYPE,
                BankReconciliationSubmittedV1.SCHEMA_VERSION,
                recon.getReconciliationId(),
                actor,
                new BankReconciliationSubmittedV1(
                        recon.getReconciliationId(),
                        recon.getGlAccountId(),
                        recon.getStatementEndDate(),
                        recon.getDifference(),
                        recon.getCurrency(),
                        count(recon.getCountUnexplainedBank()),
                        count(recon.getCountUnexplainedLedger()),
                        actor));
    }

    /** {@code .approved}: the reconciliation is FINALIZED with its approval snapshot. */
    public void approved(@NonNull BankReconciliation recon, @NonNull String actor) {
        publish(
                BankReconciliationApprovedV1.EVENT_TYPE,
                BankReconciliationApprovedV1.SCHEMA_VERSION,
                recon.getReconciliationId(),
                actor,
                new BankReconciliationApprovedV1(
                        recon.getReconciliationId(),
                        recon.getGlAccountId(),
                        recon.getStatementStartDate(),
                        recon.getStatementEndDate(),
                        recon.getAccountingPeriodCode(),
                        recon.getApprovedGlEndingBalance(),
                        recon.getAdjustedBankBalance(),
                        recon.getCurrency(),
                        actor));
    }

    /** {@code .invalidated}: an approval no longer holds. */
    public void invalidated(
            @NonNull BankReconciliation recon,
            @NonNull InvalidationReason reason,
            @Nullable UUID journalEntryId,
            @NonNull String actor) {
        publish(
                BankReconciliationInvalidatedV1.EVENT_TYPE,
                BankReconciliationInvalidatedV1.SCHEMA_VERSION,
                recon.getReconciliationId(),
                actor,
                new BankReconciliationInvalidatedV1(
                        recon.getReconciliationId(),
                        recon.getGlAccountId(),
                        BankReconciliationInvalidatedV1.Reason.valueOf(reason.name()),
                        journalEntryId));
    }

    /** {@code .superseded}: the predecessor was replaced by an approved successor. */
    public void superseded(@NonNull BankReconciliation predecessor, @NonNull UUID successorId, @NonNull String actor) {
        publish(
                BankReconciliationSupersededV1.EVENT_TYPE,
                BankReconciliationSupersededV1.SCHEMA_VERSION,
                predecessor.getReconciliationId(),
                actor,
                new BankReconciliationSupersededV1(
                        predecessor.getReconciliationId(), predecessor.getGlAccountId(), successorId));
    }

    /** {@code .cancelled}: the reconciliation was cancelled with a reason. */
    public void cancelled(@NonNull BankReconciliation recon, @NonNull String reason, @NonNull String actor) {
        publish(
                BankReconciliationCancelledV1.EVENT_TYPE,
                BankReconciliationCancelledV1.SCHEMA_VERSION,
                recon.getReconciliationId(),
                actor,
                new BankReconciliationCancelledV1(recon.getReconciliationId(), recon.getGlAccountId(), reason, actor));
    }

    private <T> void publish(String eventType, int schemaVersion, UUID aggregateId, String actor, T payload) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        DomainEventEnvelope<T> envelope = DomainEventEnvelope.of(
                eventType, schemaVersion, aggregateId, 0L, SOURCE_SERVICE, null, actor, payload, clock);
        writer.publish(ACCOUNTING_EVENTS_TOPIC, envelope);
        log.debug("Queued {} reconciliationId={}", eventType, aggregateId);
    }

    private static int count(@Nullable Integer value) {
        return value == null ? 0 : value;
    }
}
