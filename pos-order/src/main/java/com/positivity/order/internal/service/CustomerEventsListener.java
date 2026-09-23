package com.positivity.order.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.customer.BillingRulesUpdatedV1;
import com.positivity.domainevents.customer.CustomerPartyDeletedV1;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.order.internal.entity.ExtBillingRules;
import com.positivity.order.internal.entity.ExtCustomer;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.repository.ExtBillingRulesRepository;
import com.positivity.order.internal.repository.ExtCustomerRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code customer.events.v1} into the {@code ext_customer} replica (ADR-0044 §6, parity
 * story I2 replica redesign). Same contract as the pos-customer/pos-invoice replica listeners:
 * idempotent via {@code processed_events} in the upsert transaction, stale envelopes skipped,
 * transient DB errors rethrown for container retry, malformed payloads logged and skipped.
 *
 * <p>The stale guard on {@code customer.party.updated} and {@code customer.billing-rules.updated}
 * is {@link ReplicaVersionGuard} (#1486): pos-customer's party {@code aggregateVersion} strictly
 * advances, so a held row is stale only when its version is strictly greater than the incoming
 * fact's — an equal version applies, both because it is an idempotent no-op for live traffic and
 * because it is what would let a future regenerate-from-state replay repair a replica that holds
 * the version number but wrong or missing data.
 *
 * <p>Transaction shape (#2146): the listener method is not {@code @Transactional}; the handler
 * and its {@code processed_events} mark run together in a {@code REQUIRES_NEW} transaction of
 * their own. A permanent failure thrown through a transactional repository or service therefore
 * rolls back only that work and is logged and skipped, rather than marking a listener-wide
 * transaction rollback-only, whose commit would throw {@code UnexpectedRollbackException} and
 * send the record through the container's retry and dead-letter ladder. Transient database errors
 * still propagate for container retry, and since the mark commits with the work there is no
 * window in which one lands without the other.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.order.kafka", name = "enabled", havingValue = "true")
public class CustomerEventsListener {
    private static final String PAYLOAD = "payload";

    private static final String PARTY_ID = "partyId";

    private static final String CREDIT_LIMIT = "creditLimit";

    static final String OWNER = "customer";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtCustomerRepository extCustomerRepository;
    private final ExtBillingRulesRepository extBillingRulesRepository;

    /** The event's handler work and its processed mark, in a transaction of their own; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public CustomerEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtCustomerRepository extCustomerRepository,
            ExtBillingRulesRepository extBillingRulesRepository,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extCustomerRepository = extCustomerRepository;
        this.extBillingRulesRepository = extBillingRulesRepository;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${pos.order.kafka.customer-events-topic:customer.events.v1}",
            groupId = "${pos.order.kafka.customer-events-consumer-group:pos-order-customer-events}")
    public void onCustomerEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable customer event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        boolean update = CustomerPartyUpdatedV1.EVENT_TYPE.equals(eventType);
        boolean delete = CustomerPartyDeletedV1.EVENT_TYPE.equals(eventType);
        boolean billingRules = BillingRulesUpdatedV1.EVENT_TYPE.equals(eventType);
        if (!update && !delete && !billingRules) {
            log.debug("Ignoring customer event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping customer event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate customer event eventId={}", eventId);
            return;
        }

        try {
            handlerTransaction.executeWithoutResult(_ -> {
                if (update) {
                    applyUpdate(envelope);
                } else if (billingRules) {
                    applyBillingRules(envelope);
                } else {
                    applyDelete(envelope);
                }
                processedEventRepository.save(ProcessedEvent.builder()
                        .eventId(eventId)
                        .owner(OWNER)
                        .processedAt(Instant.now(clock))
                        .build());
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed customer event eventId={}", eventId, e);
        }
    }

    private void applyUpdate(JsonNode envelope) {
        JsonNode payload = envelope.path(PAYLOAD);
        UUID partyId = UUID.fromString(payload.path(PARTY_ID).stringValue(null));
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0L);

        ExtCustomer existing = extCustomerRepository.findById(partyId).orElse(null);
        // Strictly-newer-only skip: equal versions APPLY (#1486, ReplicaVersionGuard) — the
        // party's aggregateVersion strictly advances, so equal means identical content, and a
        // future replay would resend the held version deliberately to repair a replica with
        // wrong or missing rows.
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            log.debug(
                    "Skipping stale customer event for {} (v{} < v{})",
                    partyId,
                    aggregateVersion,
                    existing.getAggregateVersion());
            return;
        }
        ExtCustomer replica = existing != null ? existing : new ExtCustomer();
        replica.setPartyId(partyId);
        replica.setStatus(payload.path("status").stringValue("UNKNOWN"));
        replica.setDisplayName(payload.path("displayName").stringValue(null));
        replica.setPartyType(payload.path("partyType").stringValue(null));
        replica.setRequirementsMet(payload.path("requirementsMet").booleanValue(false));
        replica.setAggregateVersion(aggregateVersion);
        replica.setSyncedAt(Instant.now(clock));
        extCustomerRepository.save(replica);
    }

    /** Story C4 (spec R4.5): AR billing terms gate the on-account tender at checkout. */
    private void applyBillingRules(JsonNode envelope) {
        JsonNode payload = envelope.path(PAYLOAD);
        UUID partyId = UUID.fromString(payload.path(PARTY_ID).stringValue(null));
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0L);

        ExtBillingRules existing = extBillingRulesRepository.findById(partyId).orElse(null);
        // Strictly-newer-only skip: equal versions APPLY (#1486, ReplicaVersionGuard) — the
        // party's aggregateVersion strictly advances, so equal means identical content, and a
        // future replay would resend the held version deliberately to repair a replica with
        // wrong or missing rows.
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            log.debug("Skipping stale billing-rules event for {}", partyId);
            return;
        }
        ExtBillingRules replica = existing != null ? existing : new ExtBillingRules();
        replica.setPartyId(partyId);
        replica.setPaymentTerms(payload.path("paymentTerms").stringValue(null));
        replica.setCreditLimit(
                payload.path(CREDIT_LIMIT).isMissingNode()
                                || payload.path(CREDIT_LIMIT).isNull()
                        ? null
                        : payload.path(CREDIT_LIMIT).decimalValue());
        replica.setCreditHold(
                payload.path("creditHold").isNull()
                        ? null
                        : payload.path("creditHold").booleanValue(false));
        replica.setPoRequired(
                payload.path("poRequired").isNull()
                        ? null
                        : payload.path("poRequired").booleanValue(false));
        replica.setAggregateVersion(aggregateVersion);
        replica.setSyncedAt(Instant.now(clock));
        extBillingRulesRepository.save(replica);
    }

    private void applyDelete(JsonNode envelope) {
        UUID partyId = UUID.fromString(envelope.path(PAYLOAD).path(PARTY_ID).stringValue(null));
        extCustomerRepository.deleteById(partyId);
    }
}
