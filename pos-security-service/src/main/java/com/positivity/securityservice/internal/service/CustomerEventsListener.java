package com.positivity.securityservice.internal.service;

import com.positivity.domainevents.customer.CustomerPersonIdentityUpdatedV1;
import com.positivity.securityservice.internal.entity.ExtCustomerPersonIdentity;
import com.positivity.securityservice.internal.entity.ProcessedEvent;
import com.positivity.securityservice.internal.repository.ExtCustomerPersonIdentityRepository;
import com.positivity.securityservice.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code customer.events.v1} into the {@code ext_customer_person_identity} replica
 * (ADR-0044 §6, #891) backing self-registration CRM conflict signals. Only person-identity facts
 * are applied — party/billing facts on the same topic are other consumers' concerns, but their
 * eventIds are still recorded so manifest reconciliation cannot read them as drift. Idempotent
 * via {@code processed_events}; strictly-below stale guard on the emission-timestamp
 * aggregateVersion; transient errors rethrown for retry/DLQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.security-service.kafka", name = "enabled", havingValue = "true")
public class CustomerEventsListener {

    static final String OWNER = "customer";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtCustomerPersonIdentityRepository extCustomerPersonIdentityRepository;

    @KafkaListener(
            topics = "${pos.security-service.kafka.customer-events-topic:customer.events.v1}",
            groupId = "${pos.security-service.kafka.customer-events-consumer-group:pos-security-customer-events}")
    @Transactional
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
            if (CustomerPersonIdentityUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyPersonIdentityUpdated(envelope);
            } else {
                log.debug("Ignoring customer event type={}", eventType);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed customer event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
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
