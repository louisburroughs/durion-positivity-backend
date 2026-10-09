package com.positivity.order.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.tax.TaxRegistrationChangedV1;
import com.positivity.kafka.common.KafkaRails;
import com.positivity.order.internal.entity.ExtTaxRegistration;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.repository.ExtTaxRegistrationRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes pos-tax's {@code tax.events.v1} into pos-order's copy of the tenant's tax registrations (CAP:550 S32c;
 * ADR-0071 §7, ADR-0044 R3, AW58): {@code tax.registration.changed} into {@code ext_tax_registration}. pos-order
 * never calls pos-tax for registrations; this fact is the only way they arrive, and the drawer reads the copy as of
 * a business date ({@link TaxRegistrationReplica}).
 *
 * <p>Each copy row is keyed by the registration id and guarded by the fact's {@code version} ({@link
 * ReplicaVersionGuard}): an older fact changes nothing, an equal one applies, so the manifest-driven replay repairs a
 * row and a redelivery applies once. Every eventId on the topic is recorded in {@code processed_events} (owner
 * {@value #OWNER}), because pos-tax's manifest counts every fact ({@link TaxManifestListener}).
 *
 * <p>The fact carries a registration number (INTERNAL, ADR-0072 Decision 1). The copy does not keep it, since the
 * drawer needs only whether a regime is registered on a date, and nothing here logs a message, a payload or a row,
 * only ids, types and versions.
 *
 * <p>Transaction shape (#2146, ADR-0044 §4): the handler and its {@code processed_events} mark run together in a
 * {@code REQUIRES_NEW} transaction of their own. A permanent failure is logged and marked processed; transient
 * database errors propagate for container retry and dead-lettering.
 */
@Slf4j
@Component
@KafkaRails
public class TaxRegistrationEventsListener {

    static final String OWNER = "tax";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtTaxRegistrationRepository registrations;
    private final TransactionTemplate handlerTransaction;

    public TaxRegistrationEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtTaxRegistrationRepository registrations,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.registrations = registrations;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${pos.order.kafka.tax-events-topic:tax.events.v1}",
            groupId = "${pos.order.kafka.tax-events-consumer-group:pos-order-tax-events}")
    public void onTaxEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            // The message is never logged: it may carry a registration number.
            log.warn("Skipping unparsable tax event ({} characters)", message.length());
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping tax event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        try {
            handlerTransaction.executeWithoutResult(_ -> {
                if (TaxRegistrationChangedV1.EVENT_TYPE.equals(eventType)) {
                    apply(envelope);
                }
                markProcessed(eventId);
            });
        } catch (Exception e) {
            if (RetryableConsumerFailures.isRetryable(e)) {
                // The container retries with backoff, then publishes to {topic}.dlq (ADR-0044 §4).
                throw e;
            }
            log.warn(
                    "Skipping malformed tax event eventId={} eventType={}: {}",
                    eventId,
                    eventType,
                    e.getClass().getSimpleName());
            handlerTransaction.executeWithoutResult(_ -> markProcessed(eventId));
        }
    }

    private void apply(JsonNode envelope) {
        TaxRegistrationChangedV1 fact =
                objectMapper.treeToValue(envelope.path("payload"), TaxRegistrationChangedV1.class);
        ExtTaxRegistration existing =
                registrations.findById(fact.registrationId()).orElse(null);
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), fact.version())) {
            log.debug(
                    "Ignoring stale tax.registration.changed registration={} held={} incoming={}",
                    fact.registrationId(),
                    existing.getAggregateVersion(),
                    fact.version());
            return;
        }
        ExtTaxRegistration copy = existing != null ? existing : new ExtTaxRegistration();
        copy.setRegistrationId(fact.registrationId());
        copy.setCountryCode(fact.countryCode());
        copy.setRegime(fact.regime());
        copy.setJurisdictionCode(fact.jurisdictionCode());
        copy.setEffectiveFrom(fact.effectiveFrom());
        copy.setEffectiveTo(fact.effectiveTo());
        copy.setAggregateVersion(fact.version());
        copy.setSyncedAt(Instant.now(clock));
        registrations.save(copy);
    }

    private void markProcessed(String eventId) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }
}
