package com.positivity.invoice.internal.service;

import com.positivity.domainevents.customer.CustomerPartyDeletedV1;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.invoice.internal.entity.ExtCustomerPartyReplica;
import com.positivity.invoice.internal.entity.ProcessedEvent;
import com.positivity.invoice.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.invoice.internal.repository.ProcessedEventRepository;
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
 * Consumes {@code customer.events.v1} into the {@code ext_customer_party} replica (ADR-0044 §6,
 * #891). Only party updated/deleted facts are handled — person-identity and billing-rules facts
 * on the same topic are other consumers' concerns. Idempotent via {@code processed_events};
 * strictly-below stale guard on the emission-timestamp aggregateVersion; transient errors
 * rethrown for retry/DLQ.
 *
 * <p><b>Transaction shape (#2146).</b> The listener method is not {@code @Transactional}: the
 * handler and its {@code processed_events} mark commit together in their own {@code REQUIRES_NEW}
 * transaction, so neither can commit without the other. A permanent failure rolls back only that
 * work and is then recorded in a separate transaction, instead of leaving a shared transaction
 * rollback-only and sending the record through the container's retry ladder to the DLQ. Transient
 * failures still propagate for container retry.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.invoice.kafka", name = "enabled", havingValue = "true")
public class CustomerEventsListener {

    static final String OWNER = "customer";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtCustomerPartyReplicaRepository extCustomerPartyReplicaRepository;
    private final Counter payloadRejectedCounter;

    /** The handler and its processed mark commit in one transaction; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public CustomerEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtCustomerPartyReplicaRepository extCustomerPartyReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extCustomerPartyReplicaRepository = extCustomerPartyReplicaRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", OWNER)
                        .tag("entity", "customer-events")
                        .register(registry);
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${pos.invoice.kafka.customer-events-topic:customer.events.v1}",
            groupId = "${pos.invoice.kafka.customer-events-consumer-group:pos-invoice-customer-events}")
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
                switch (eventType == null ? "" : eventType) {
                    case CustomerPartyUpdatedV1.EVENT_TYPE -> applyPartyUpdated(envelope);
                    case CustomerPartyDeletedV1.EVENT_TYPE -> applyPartyDeleted(envelope);
                    // Ignored types still fall through to the processed_events insert below:
                    // the owner's manifest counts every fact in the window, so skipping the
                    // record would read as permanent drift and trigger useless replays.
                    default -> log.debug("Ignoring customer event type={} eventId={}", eventType, eventId);
                }
                recordProcessed(eventId);
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed customer event payload eventId={}: {}", eventId, e.getMessage(), e);
            handlerTransaction.executeWithoutResult(_ -> recordProcessed(eventId));
        } catch (Exception e) {
            log.warn("Skipping malformed customer event eventId={}", eventId, e);
            handlerTransaction.executeWithoutResult(_ -> recordProcessed(eventId));
        }
    }

    private void recordProcessed(@NonNull String eventId) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyPartyUpdated(JsonNode envelope) {
        CustomerPartyUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), CustomerPartyUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtCustomerPartyReplica existing =
                extCustomerPartyReplicaRepository.findById(payload.partyId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extCustomerPartyReplicaRepository.save(ExtCustomerPartyReplica.builder()
                .partyId(payload.partyId())
                .partyType(payload.partyType())
                .displayName(payload.displayName())
                .status(payload.status())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info("Updated ext_customer_party partyId={} version={}", payload.partyId(), aggregateVersion);
    }

    private void applyPartyDeleted(JsonNode envelope) {
        CustomerPartyDeletedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), CustomerPartyDeletedV1.class);
        extCustomerPartyReplicaRepository.deleteById(payload.partyId());
        log.info("Deleted ext_customer_party partyId={}", payload.partyId());
    }
}
