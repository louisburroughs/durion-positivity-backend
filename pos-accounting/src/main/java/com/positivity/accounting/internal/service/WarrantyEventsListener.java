package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.entity.WarrantyReimbursementExpectation;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.WarrantyReimbursementExpectationRepository;
import com.positivity.domainevents.warranty.WarrantyReimbursementResolvedV1;
import com.positivity.domainevents.warranty.WarrantyReimbursementSubmittedV1;
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
 * Consumes {@code warranty.events.v1} into accounting's {@code warranty_reimbursement_expectation}
 * expected-credit records (issue #927).
 *
 * <p>Same contract as {@link InvoiceEventsListener}: idempotent via {@code processed_events} in
 * the upsert transaction, stale envelopes (aggregateVersion at or below the row's) skipped,
 * transient DB errors rethrown for container retry/DLQ, malformed payloads logged and skipped.
 * The topic also carries claim-lifecycle facts ({@code warranty.claim.settled},
 * {@code warranty.claim.snapshot}, part-return facts) this module ignores — their eventIds are
 * still recorded so redelivery stays cheap.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class WarrantyEventsListener {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final WarrantyReimbursementExpectationRepository expectationRepository;

    @KafkaListener(
            topics = "${pos.accounting.kafka.warranty-events-topic:warranty.events.v1}",
            groupId = "pos-accounting-warranty-events")
    @Transactional
    public void onWarrantyEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable warranty event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping warranty event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate warranty event eventId={}", eventId);
            return;
        }

        try {
            if (WarrantyReimbursementSubmittedV1.EVENT_TYPE.equals(eventType)) {
                applyReimbursementSubmitted(envelope);
            } else if (WarrantyReimbursementResolvedV1.EVENT_TYPE.equals(eventType)) {
                applyReimbursementResolved(envelope);
            } else {
                // Ignored types still fall through to the processed_events insert below.
                log.debug("Ignoring warranty event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            // Retry with backoff / DLQ via the container error handler (ADR-0044 §4).
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed warranty event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyReimbursementSubmitted(JsonNode envelope) {
        WarrantyReimbursementSubmittedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), WarrantyReimbursementSubmittedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);

        WarrantyReimbursementExpectation existing =
                expectationRepository.findById(payload.reimbursementId()).orElse(null);
        if (isStale(existing, aggregateVersion, payload.reimbursementId().toString())) {
            return;
        }

        expectationRepository.save(WarrantyReimbursementExpectation.builder()
                .reimbursementId(payload.reimbursementId())
                .claimId(payload.claimId())
                .claimCode(payload.claimCode())
                .providerId(payload.providerId())
                .apVendorId(payload.apVendorId())
                .amountRequested(payload.amountRequested())
                .vendorClaimReference(payload.vendorClaimReference())
                .status(WarrantyReimbursementExpectation.STATUS_EXPECTED)
                .submittedAt(payload.submittedAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info(
                "Recorded warranty reimbursement expectation reimbursementId={} claimCode={} version={}",
                payload.reimbursementId(),
                payload.claimCode(),
                aggregateVersion);
    }

    private void applyReimbursementResolved(JsonNode envelope) {
        WarrantyReimbursementResolvedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), WarrantyReimbursementResolvedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);

        WarrantyReimbursementExpectation existing =
                expectationRepository.findById(payload.reimbursementId()).orElse(null);
        if (isStale(existing, aggregateVersion, payload.reimbursementId().toString())) {
            return;
        }

        // Upsert-tolerant: if the submitted fact was missed, create the row from what the
        // resolution carries (amountRequested/submittedAt stay null — pos-warranty remains the
        // system of record for the full lifecycle).
        WarrantyReimbursementExpectation.WarrantyReimbursementExpectationBuilder builder = existing != null
                ? existing.toBuilder()
                : WarrantyReimbursementExpectation.builder()
                        .reimbursementId(payload.reimbursementId())
                        .claimId(payload.claimId())
                        .claimCode(payload.claimCode())
                        .providerId(payload.providerId())
                        .apVendorId(payload.apVendorId());
        expectationRepository.save(builder.status(payload.status())
                .amountApproved(payload.amountApproved())
                .creditReference(payload.creditReference())
                .resolvedAt(payload.resolvedAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info(
                "Resolved warranty reimbursement expectation reimbursementId={} status={} version={}",
                payload.reimbursementId(),
                payload.status(),
                aggregateVersion);
    }

    private boolean isStale(WarrantyReimbursementExpectation existing, long aggregateVersion, String reimbursementId) {
        // Claim aggregate versions are strictly increasing per emitted fact, so an equal or lower
        // version is a replay and must never overwrite a newer row (mirrors InvoiceEventsListener).
        if (existing != null && existing.getAggregateVersion() >= aggregateVersion) {
            log.debug(
                    "Skipping stale warranty reimbursement event reimbursementId={} eventVersion={} rowVersion={}",
                    reimbursementId,
                    aggregateVersion,
                    existing.getAggregateVersion());
            return true;
        }
        return false;
    }
}
