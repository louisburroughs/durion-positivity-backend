package com.positivity.invoice.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.payment.SettlementProviderConfigV1;
import com.positivity.domainevents.payment.SettlementReportedV1;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Emits the normalized settlement contracts to pos-accounting via the invoice transactional outbox
 * (story F1b, issue #962, decisions D-9/D-10). Settlement facts go to the payment fact topic
 * ({@code payment.events.v1}); provider configuration goes to the compacted config topic
 * ({@code payment.settlement-config.v1}) keyed for last-writer-wins per provider (decision D-9).
 *
 * <p>No-op when the invoice Kafka feature flag ({@code pos.invoice.kafka.enabled}) is off — the
 * {@link OutboxEventWriter} bean is conditional, so this publisher degrades gracefully. Must be
 * called inside the mutating transaction (the writer requires {@code MANDATORY} propagation).
 *
 * <p>The {@link DomainEventEnvelope} record key is a UUID; the settlement id and provider code are
 * strings, so a stable UUID is derived per natural key (parse when UUID-shaped, else a name-based
 * UUID). This preserves per-settlement ordering and, on the compacted config topic, a stable
 * per-provider key so compaction keeps the latest config. pos-accounting keys its config replica off
 * the payload {@code providerCode}, not the record key.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementEventPublisher {

    static final String PAYMENT_DOMAIN = "payment";
    static final String SETTLEMENT_CONFIG_TOPIC = "payment.settlement-config.v1";
    static final String SOURCE_SERVICE = "pos-invoice";

    private final Clock clock;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    /** Emit one settlement fact to {@code payment.events.v1}, keyed by settlement id. */
    public void publishSettlementReported(@NonNull SettlementReportedV1 payload) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        DomainEventEnvelope<SettlementReportedV1> envelope = DomainEventEnvelope.of(
                SettlementReportedV1.EVENT_TYPE,
                SettlementReportedV1.SCHEMA_VERSION,
                recordKey(payload.settlementId()),
                0L,
                SOURCE_SERVICE,
                null,
                null,
                payload,
                clock);
        writer.publish(DomainTopics.events(PAYMENT_DOMAIN), envelope);
        log.debug(
                "Queued {} settlementId={} provider={}",
                SettlementReportedV1.EVENT_TYPE,
                payload.settlementId(),
                payload.providerCode());
    }

    /** Emit the full current provider config to the compacted {@code payment.settlement-config.v1}. */
    public void publishProviderConfig(@NonNull SettlementProviderConfigV1 payload) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        DomainEventEnvelope<SettlementProviderConfigV1> envelope = DomainEventEnvelope.of(
                SettlementProviderConfigV1.EVENT_TYPE,
                SettlementProviderConfigV1.SCHEMA_VERSION,
                recordKey(payload.providerCode()),
                0L,
                SOURCE_SERVICE,
                null,
                null,
                payload,
                clock);
        writer.publish(SETTLEMENT_CONFIG_TOPIC, envelope);
        log.debug("Queued {} providerCode={}", SettlementProviderConfigV1.EVENT_TYPE, payload.providerCode());
    }

    /** Derive a stable UUID record key from a natural key (settlement id / provider code). */
    private static UUID recordKey(@NonNull String naturalKey) {
        try {
            return UUID.fromString(naturalKey);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(naturalKey.getBytes(StandardCharsets.UTF_8));
        }
    }
}
