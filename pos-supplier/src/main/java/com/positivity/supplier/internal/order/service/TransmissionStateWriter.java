package com.positivity.supplier.internal.order.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.supplier.SupplierOrderConfirmedV1;
import com.positivity.domainevents.supplier.SupplierOrderRejectedV1;
import com.positivity.domainevents.supplier.SupplierOrderReviewRequiredV1;
import com.positivity.domainevents.supplier.SupplierOrderStatusChangedV1;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.domain.model.SupplierDeliverySchedule;
import com.positivity.supplier.internal.domain.model.SupplierOrderResult;
import com.positivity.supplier.internal.domain.model.SupplierOrderStatusResult;
import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.enums.TransmissionAttemptState;
import com.positivity.supplier.internal.repository.SupplierTransmissionIntentRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Every state transition of a transmission intent, and the events that describe it, committed
 * together (ADR-0044 §4, ADR-0052 §2).
 *
 * <h2>Separate from the dispatcher on purpose</h2>
 *
 * A {@code @Transactional} method called from a sibling method of the same bean is not proxied,
 * so "the state and its event commit together" would silently not be true. It is also what makes
 * {@link #markDispatching} able to use {@code REQUIRES_NEW} — the transition has to be
 * <em>committed</em> before the network call the caller is about to make, and a caller-scoped
 * transaction would hold it open across the very I/O it is supposed to precede.
 *
 * <h2>Result events are published, never state changes alone</h2>
 *
 * Confirmation and rejection are facts other domains act on — a purchase order goes on order, or
 * comes back off it — so they go through the outbox in the transaction that recorded them. A
 * status observation publishes only when the vendor's answer actually changed, because a poller
 * asking every few minutes would otherwise republish an unchanged order all day and drown the
 * timeline it is supposed to inform.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransmissionStateWriter {

    private static final String SOURCE = "pos-supplier";

    private final SupplierTransmissionIntentRepository intentRepository;
    private final SupplierOutboxEventWriter outboxWriter;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Commits {@code PENDING → DISPATCHING} in its own transaction, before any network I/O.
     *
     * <p>This is the single most load-bearing line in the module. Once this commits, no code path
     * automatically re-sends this intent: a crash immediately afterwards leaves a row that
     * recovery reconciles or escalates, rather than a row that looks never-sent and gets sent
     * again (ADR-0052 §2).
     *
     * @return false when the intent was not in {@code PENDING} — another instance took it
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDispatching(@NonNull UUID transmissionIntentId, @NonNull String correlationId) {
        SupplierTransmissionIntentEntity intent =
                intentRepository.findById(transmissionIntentId).orElse(null);
        if (intent == null || intent.getAttemptState() != TransmissionAttemptState.PENDING) {
            return false;
        }
        intent.setAttemptState(TransmissionAttemptState.DISPATCHING);
        intent.setDispatchAttempts(intent.getDispatchAttempts() + 1);
        intent.setDispatchedAt(Instant.now(clock));
        intent.setCorrelationId(correlationId);
        intentRepository.save(intent);
        return true;
    }

    /**
     * Records the vendor's decision on an order create and publishes the matching result event.
     *
     * @param transmissionIntentId the intent that was dispatched
     * @param result the vendor's decision
     */
    @Transactional
    public void recordCreateResult(@NonNull UUID transmissionIntentId, @NonNull SupplierOrderResult result) {
        SupplierTransmissionIntentEntity intent = require(transmissionIntentId);
        intent.setSupplierOrderNumber(truncate(result.supplierOrderNumber(), 64));
        intent.setVendorErrorCode(truncate(result.vendorErrorCode(), 16));
        intent.setVendorReason(truncate(result.vendorReason(), 2000));
        intent.setResultAt(Instant.now(clock));
        intent.setLastError(null);

        if (result.status() == SupplierOrderResult.Status.CONFIRMED) {
            intent.setAttemptState(TransmissionAttemptState.CONFIRMED);
            // Polling starts here, not at delivery: everything receiving needs — confirmed dates,
            // despatches, ASN references — arrives after this point (#1318).
            intent.setStatusPollingActive(true);
            intent.setPollingStoppedReason(null);
            intent.setLatestScheduledDeliveryDate(latestDeliveryDate(result.lines()));
            publishConfirmed(intent, SupplierOrderConfirmedV1.Source.ORDER_RESPONSE, result.lines());
        } else {
            intent.setAttemptState(TransmissionAttemptState.REJECTED);
            intent.setStatusPollingActive(false);
            intent.setPollingStoppedReason(OrderStatusCompletion.StopReason.VENDOR_REJECTED.name());
            // A refused order releases its claim on the tuple: re-ordering the same revision is
            // legitimate, and does it under a new intent with a new document id (ADR-0052 §4).
            intent.setActiveIntentKey(null);
            publishRejected(
                    intent,
                    SupplierOrderRejectedV1.Reason.VENDOR_REJECTED,
                    result.vendorErrorCode(),
                    result.vendorReason(),
                    result.lines());
        }
        intentRepository.save(intent);
    }

    /**
     * Applies what an order-status query established about an ambiguous transmission
     * (ADR-0052 §3).
     *
     * <p>Four answers, four dispositions, and none of them re-sends the order:
     *
     * <ul>
     *   <li><strong>REJECTED</strong> — the vendor refused it; the transmission ends as a
     *       rejection, exactly as if the order response had said so.
     *   <li><strong>CONFIRMED</strong> — the vendor holds and accepted it. Recorded as confirmed
     *       with {@code STATUS_RECONCILIATION} as the source, so a timeline can show that this
     *       confirmation was recovered rather than received.
     *   <li><strong>IN_PROGRESS</strong> — the vendor holds it but has decided nothing.
     *       {@code SENT_AWAITING_RESULT}, polling on, and <em>no event</em>: there is no outcome
     *       to report, and inventing one would be worse than waiting.
     *   <li><strong>NOT_FOUND</strong> — handled by the caller, because "the vendor does not have
     *       an order we may have sent" is the one answer this module must never resolve on its own
     *       (ADR-0052 §3/§4).
     * </ul>
     *
     * @return the state the intent was moved to
     */
    @Transactional
    public TransmissionAttemptState recordReconciliation(
            @NonNull UUID transmissionIntentId, @NonNull SupplierOrderStatusResult result) {
        SupplierTransmissionIntentEntity intent = require(transmissionIntentId);
        Instant now = Instant.now(clock);
        intent.setResultAt(now);
        intent.setLastPolledAt(now);
        intent.setLastStatusState(result.status().name());
        if (result.supplierOrderNumber() != null) {
            intent.setSupplierOrderNumber(truncate(result.supplierOrderNumber(), 64));
        }

        switch (result.status()) {
            case REJECTED -> {
                intent.setAttemptState(TransmissionAttemptState.REJECTED);
                intent.setStatusPollingActive(false);
                intent.setPollingStoppedReason(OrderStatusCompletion.StopReason.VENDOR_REJECTED.name());
                intent.setVendorErrorCode(truncate(result.vendorErrorCode(), 16));
                intent.setVendorReason(truncate(result.vendorReason(), 2000));
                intent.setActiveIntentKey(null);
                publishRejected(
                        intent,
                        SupplierOrderRejectedV1.Reason.VENDOR_REJECTED,
                        result.vendorErrorCode(),
                        result.vendorReason(),
                        List.of());
            }
            case CONFIRMED -> {
                intent.setAttemptState(TransmissionAttemptState.CONFIRMED);
                intent.setStatusPollingActive(true);
                intent.setPollingStoppedReason(null);
                intent.setLatestScheduledDeliveryDate(latestScheduledDate(result));
                publishConfirmedLines(
                        intent,
                        SupplierOrderConfirmedV1.Source.STATUS_RECONCILIATION,
                        OrderEventMapper.toEventConfirmedLinesFromStatus(result.lines()));
            }
            case IN_PROGRESS -> {
                intent.setAttemptState(TransmissionAttemptState.SENT_AWAITING_RESULT);
                intent.setStatusPollingActive(true);
                intent.setPollingStoppedReason(null);
            }
            case NOT_FOUND ->
                throw new IllegalArgumentException(
                        "NOT_FOUND is the caller's decision to make, not a reconciliation outcome (ADR-0052 §3)");
        }
        intentRepository.save(intent);
        return intent.getAttemptState();
    }

    /**
     * Escalates an intent to {@code MANUAL_REVIEW} (ADR-0052 §3/§4).
     *
     * <p>No <em>outcome</em> is published, and that remains deliberate: this service does not know
     * whether the vendor received the order, and saying "rejected" or "confirmed" here would be an
     * assertion nobody has made.
     *
     * <p>The state itself is published, which is a different claim. Left silent, the ordering
     * domain sees a request that looks in flight and is indistinguishable from one genuinely in
     * progress — so a buyer waits on goods that are not coming, and the only record of why lives
     * in a transmission log they have no reason to open (CAP-320 #1330). "Still open" and "stopped,
     * pending a human" are different facts, and the second is the one somebody has to act on.
     */
    @Transactional
    public void recordManualReview(@NonNull UUID transmissionIntentId, @Nullable String detail) {
        SupplierTransmissionIntentEntity intent = require(transmissionIntentId);
        intent.setAttemptState(TransmissionAttemptState.MANUAL_REVIEW);
        intent.setStatusPollingActive(false);
        intent.setLastError(truncate(detail, 2000));
        intentRepository.save(intent);
        publishReviewRequired(intent, detail);
        log.warn(
                "Transmission intent {} (document {}) entered MANUAL_REVIEW: {}",
                intent.getTransmissionIntentId(),
                intent.getDocumentId(),
                detail);
    }

    /**
     * Returns an intent to {@code PENDING} after a failure that demonstrably preceded
     * transmission (ADR-0052 §3/§5).
     *
     * <p>Only ever called for {@link com.positivity.supplier.internal.spi.ExchangeOutcome#PRE_SEND_FAILURE}
     * and for encode failures, both of which mean the vendor cannot know about the document. The
     * same document id is re-presented on the next attempt, which is the point of deriving it from
     * the intent.
     */
    @Transactional
    public void recordPreSendFailure(@NonNull UUID transmissionIntentId, @Nullable String detail) {
        SupplierTransmissionIntentEntity intent = require(transmissionIntentId);
        intent.setAttemptState(TransmissionAttemptState.PENDING);
        intent.setLastError(truncate(detail, 2000));
        intentRepository.save(intent);
    }

    /**
     * Records one order-status observation, publishing
     * {@code supplier.orderstatus.changed} only when the vendor's answer differs from the last
     * one, and stopping polling when there is nothing left to learn.
     *
     * @param transmissionIntentId the intent that was polled
     * @param result the vendor's answer
     * @param maxQuietDays days of unchanged answers after which polling stops for staleness
     * @return true when a change was published
     */
    @Transactional
    public boolean recordStatusObservation(
            @NonNull UUID transmissionIntentId, @NonNull SupplierOrderStatusResult result, int maxQuietDays) {
        SupplierTransmissionIntentEntity intent = require(transmissionIntentId);
        Instant observedAt = Instant.now(clock);
        intent.setLastPolledAt(observedAt);

        String fingerprint = fingerprint(result);
        boolean changed = !fingerprint.equals(intent.getLastStatusFingerprint());

        if (changed) {
            intent.setLastStatusFingerprint(fingerprint);
            intent.setLastStatusAt(observedAt);
            intent.setLastStatusState(result.status().name());
            if (result.supplierOrderNumber() != null) {
                intent.setSupplierOrderNumber(truncate(result.supplierOrderNumber(), 64));
            }
            intent.setLatestScheduledDeliveryDate(latestScheduledDate(result));
            publishStatusChanged(intent, result, observedAt);
        }

        OrderStatusCompletion.StopReason stop = OrderStatusCompletion.completionOf(result);
        if (stop == null
                && OrderStatusCompletion.isStale(
                        toDate(intent.getLastStatusAt()),
                        LocalDate.now(clock.withZone(ZoneOffset.UTC)),
                        maxQuietDays)) {
            stop = OrderStatusCompletion.StopReason.STALE;
        }
        if (stop != null) {
            intent.setStatusPollingActive(false);
            intent.setPollingStoppedReason(stop.name());
            log.info(
                    "Order status polling stopped for intent {} (document {}): {}",
                    intent.getTransmissionIntentId(),
                    intent.getDocumentId(),
                    stop);
        }

        intentRepository.save(intent);
        return changed;
    }

    /**
     * Applies an operator's ADR-0052 §4 resolution, recording actor and evidence and publishing
     * the result event the ordering domain's timeline needs.
     *
     * <p>The three actions are deliberately expressed as the existing result events rather than a
     * new event type: the ordering domain's question is "did this order reach the vendor", and the
     * answer is the same fact whether a vendor's response or a human established it. Which of the
     * two it was is carried on the payload — {@code Source.MANUAL_RESOLUTION} and the
     * {@code MANUAL_*} rejection reasons — so a timeline can show the difference without the
     * contract table growing an entry ADR-0049 §3 does not have.
     */
    @Transactional
    public void recordResolution(
            @NonNull UUID transmissionIntentId,
            @NonNull TransmissionAttemptState resolvedState,
            @NonNull String action,
            @NonNull String actor,
            @Nullable String evidence,
            @Nullable String supplierOrderNumber) {
        SupplierTransmissionIntentEntity intent = require(transmissionIntentId);
        Instant now = Instant.now(clock);
        intent.setAttemptState(resolvedState);
        intent.setResolutionAction(action);
        intent.setResolvedBy(truncate(actor, 100));
        intent.setResolvedAt(now);
        intent.setResolutionEvidence(truncate(evidence, 2000));
        intent.setResultAt(now);

        if (resolvedState == TransmissionAttemptState.CONFIRMED) {
            if (supplierOrderNumber != null) {
                intent.setSupplierOrderNumber(truncate(supplierOrderNumber, 64));
            }
            // The vendor holds the order, so the fulfilment story is still worth watching — the
            // same reason a vendor-sourced confirmation starts polling.
            intent.setStatusPollingActive(true);
            intent.setPollingStoppedReason(null);
            publishConfirmed(intent, SupplierOrderConfirmedV1.Source.MANUAL_RESOLUTION, List.of());
        } else {
            intent.setStatusPollingActive(false);
            intent.setPollingStoppedReason(OrderStatusCompletion.StopReason.MANUALLY_RESOLVED.name());
            intent.setActiveIntentKey(null);
            publishRejected(
                    intent,
                    resolvedState == TransmissionAttemptState.FAILED
                            ? SupplierOrderRejectedV1.Reason.MANUAL_NOT_RECEIVED
                            : SupplierOrderRejectedV1.Reason.MANUAL_CANCELLED,
                    null,
                    evidence,
                    List.of());
        }
        intentRepository.save(intent);
    }

    private void publishConfirmed(
            SupplierTransmissionIntentEntity intent,
            SupplierOrderConfirmedV1.Source source,
            List<SupplierOrderResult.Line> lines) {
        publishConfirmedLines(intent, source, OrderEventMapper.toEventConfirmedLines(lines));
    }

    private void publishReviewRequired(SupplierTransmissionIntentEntity intent, String detail) {
        SupplierOrderReviewRequiredV1 payload = new SupplierOrderReviewRequiredV1(
                intent.getTransmissionIntentId(),
                intent.getPurchaseOrderId(),
                intent.getVendorProfileId(),
                intent.getSupplierRef(),
                intent.getDocumentId(),
                truncate(detail, 1000),
                Instant.now(clock));
        publish(
                intent,
                SupplierOrderReviewRequiredV1.EVENT_TYPE,
                SupplierOrderReviewRequiredV1.SCHEMA_VERSION,
                payload);
    }

    private void publishConfirmedLines(
            SupplierTransmissionIntentEntity intent,
            SupplierOrderConfirmedV1.Source source,
            List<com.positivity.domainevents.supplier.SupplierOrderConfirmedLine> lines) {
        SupplierOrderConfirmedV1 payload = new SupplierOrderConfirmedV1(
                intent.getTransmissionIntentId(),
                intent.getPurchaseOrderId(),
                intent.getVendorProfileId(),
                intent.getSupplierRef(),
                intent.getDocumentId(),
                intent.getSupplierOrderNumber(),
                source,
                Instant.now(clock),
                lines);
        publish(intent, SupplierOrderConfirmedV1.EVENT_TYPE, SupplierOrderConfirmedV1.SCHEMA_VERSION, payload);
    }

    private void publishRejected(
            SupplierTransmissionIntentEntity intent,
            SupplierOrderRejectedV1.Reason reason,
            @Nullable String vendorErrorCode,
            @Nullable String vendorReason,
            List<SupplierOrderResult.Line> lines) {
        SupplierOrderRejectedV1 payload = new SupplierOrderRejectedV1(
                intent.getTransmissionIntentId(),
                intent.getPurchaseOrderId(),
                intent.getVendorProfileId(),
                intent.getSupplierRef(),
                intent.getDocumentId(),
                reason,
                vendorErrorCode,
                vendorReason,
                Instant.now(clock),
                OrderEventMapper.toEventConfirmedLines(lines));
        publish(intent, SupplierOrderRejectedV1.EVENT_TYPE, SupplierOrderRejectedV1.SCHEMA_VERSION, payload);
    }

    private void publishStatusChanged(
            SupplierTransmissionIntentEntity intent, SupplierOrderStatusResult result, Instant observedAt) {
        SupplierOrderStatusChangedV1 payload = new SupplierOrderStatusChangedV1(
                intent.getTransmissionIntentId(),
                intent.getPurchaseOrderId(),
                intent.getVendorProfileId(),
                intent.getSupplierRef(),
                intent.getDocumentId(),
                result.supplierOrderNumber() == null ? intent.getSupplierOrderNumber() : result.supplierOrderNumber(),
                OrderEventMapper.toEventStatus(result.status()),
                result.vendorReason(),
                observedAt,
                OrderEventMapper.toEventStatusLines(result.lines()));
        publish(intent, SupplierOrderStatusChangedV1.EVENT_TYPE, SupplierOrderStatusChangedV1.SCHEMA_VERSION, payload);
    }

    private void publish(SupplierTransmissionIntentEntity intent, String eventType, int schemaVersion, Object payload) {
        intent.setEventSequence(intent.getEventSequence() + 1);
        outboxWriter.publish(
                DomainTopics.events("supplier"),
                new DomainEventEnvelope<>(
                        UUIDv7Generator.generate(),
                        eventType,
                        schemaVersion,
                        intent.getTransmissionIntentId(),
                        intent.getEventSequence(),
                        Instant.now(clock),
                        SOURCE,
                        intent.getCorrelationId(),
                        intent.getCreatedBy(),
                        payload));
    }

    /**
     * A stable digest of the vendor's answer.
     *
     * <p>Serialising the canonical record is what makes this stable: record component order is
     * fixed by the declaration, so the same answer always produces the same bytes, and any change
     * anywhere in the tree — a new despatch, a moved delivery date, a quantity going from null to
     * zero — changes the digest. Comparing digests rather than storing answers keeps a polling
     * loop from accumulating commercial documents outside the audit's retention rules.
     */
    private String fingerprint(SupplierOrderStatusResult result) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(result);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to fingerprint order status answers", e);
        } catch (Exception e) {
            // Deliberately fatal for this observation rather than "assume unchanged": a fingerprint
            // that silently degrades would stop the timeline updating, and the failure would look
            // like a vendor that never despatches anything. The poll rolls back and retries.
            throw new IllegalStateException("Unable to fingerprint an order status answer", e);
        }
    }

    @Nullable
    private static LocalDate latestScheduledDate(SupplierOrderStatusResult result) {
        LocalDate latest = null;
        for (SupplierOrderStatusResult.Line line : result.lines()) {
            for (SupplierDeliverySchedule schedule : line.schedules()) {
                latest = later(latest, schedule.deliveryDate());
            }
        }
        return latest;
    }

    @Nullable
    private static LocalDate latestDeliveryDate(List<SupplierOrderResult.Line> lines) {
        LocalDate latest = null;
        for (SupplierOrderResult.Line line : lines) {
            for (SupplierDeliverySchedule schedule : line.schedules()) {
                latest = later(latest, schedule.deliveryDate());
            }
        }
        return latest;
    }

    @Nullable
    private static LocalDate later(@Nullable LocalDate current, @Nullable LocalDate candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    @Nullable
    private static LocalDate toDate(@Nullable Instant instant) {
        return instant == null ? null : instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    @NonNull
    private SupplierTransmissionIntentEntity require(UUID transmissionIntentId) {
        return intentRepository
                .findById(transmissionIntentId)
                .orElseThrow(() ->
                        new IllegalStateException("No transmission intent exists with id " + transmissionIntentId));
    }

    @Nullable
    private static String truncate(@Nullable String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
