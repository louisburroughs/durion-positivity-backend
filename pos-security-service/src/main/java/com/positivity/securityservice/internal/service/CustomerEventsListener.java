package com.positivity.securityservice.internal.service;

import com.positivity.domainevents.customer.CustomerPersonIdentityUpdatedV1;
import com.positivity.securityservice.internal.entity.ExtCustomerPersonIdentity;
import com.positivity.securityservice.internal.entity.ProcessedEvent;
import com.positivity.securityservice.internal.repository.ExtCustomerPersonIdentityRepository;
import com.positivity.securityservice.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code customer.events.v1} into the {@code ext_customer_person_identity} replica
 * (ADR-0044 §6, #891) backing self-registration CRM conflict signals. Only person-identity facts
 * are applied — party/billing facts on the same topic are other consumers' concerns, but their
 * eventIds are still recorded so manifest reconciliation cannot read them as drift. Idempotent
 * via {@code processed_events}; strictly-below stale guard on the emission-timestamp
 * aggregateVersion; transient errors rethrown for retry/DLQ.
 *
 * <p><strong>Transaction shape (#2146).</strong> The listener method is not {@code @Transactional}:
 * the handler's work and the {@code processed_events} mark commit together in a
 * {@code REQUIRES_NEW} transaction of their own, so neither lands without the other. A permanent
 * failure rolls back only that work and is recorded in a separate transaction, instead of
 * poisoning a shared transaction whose commit then throws and sends the record through retry and
 * dead-lettering. Transient database errors still propagate, unrecorded, for container retry.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.security-service.kafka", name = "enabled", havingValue = "true")
public class CustomerEventsListener {

    static final String OWNER = "customer";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtCustomerPersonIdentityRepository extCustomerPersonIdentityRepository;
    private final Counter payloadRejectedCounter;

    /** Runs the handler with its processed mark, and records a failure; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public CustomerEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtCustomerPersonIdentityRepository extCustomerPersonIdentityRepository,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extCustomerPersonIdentityRepository = extCustomerPersonIdentityRepository;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", OWNER)
                        .tag("entity", "customer-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.security-service.kafka.customer-events-topic:customer.events.v1}",
            groupId = "${pos.security-service.kafka.customer-events-consumer-group:pos-security-customer-events}")
    public void onCustomerEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable customer event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping customer event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            handlerTransaction.executeWithoutResult(_ -> {
                if (CustomerPersonIdentityUpdatedV1.EVENT_TYPE.equals(eventType)) {
                    applyPersonIdentityUpdated(envelope);
                } else {
                    log.debug("Ignoring customer event type={} eventId={}", eventType, eventId);
                }
                processedEventRepository.save(processedMark(eventId));
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed customer event payload eventId={}: {}", eventId, e.getMessage(), e);
            recordFailed(eventId);
        } catch (Exception e) {
            log.warn("Skipping malformed customer event eventId={}", eventId, e);
            recordFailed(eventId);
        }
    }

    private ProcessedEvent processedMark(String eventId) {
        return ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build();
    }

    /** Records a permanently failed event in a transaction of its own; see the class doc. */
    private void recordFailed(String eventId) {
        handlerTransaction.executeWithoutResult(_ -> processedEventRepository.save(processedMark(eventId)));
    }

    private void applyPersonIdentityUpdated(JsonNode envelope) {
        CustomerPersonIdentityUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), CustomerPersonIdentityUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtCustomerPersonIdentity existing =
                extCustomerPersonIdentityRepository.findById(payload.personId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extCustomerPersonIdentityRepository.save(ExtCustomerPersonIdentity.builder()
                .personId(payload.personId())
                .personPartyId(payload.personPartyId())
                .individualCustomer(payload.individualCustomer())
                .commercialContact(payload.commercialContact())
                .commercialAccountCount(payload.commercialAccountCount())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info(
                "Updated ext_customer_person_identity personId={} individualCustomer={} commercialContact={}",
                payload.personId(),
                payload.individualCustomer(),
                payload.commercialContact());
    }
}
