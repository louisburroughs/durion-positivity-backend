package com.positivity.supplier.internal.order.service;

import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.enums.TransmissionAttemptState;
import com.positivity.supplier.internal.repository.SupplierTransmissionIntentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the three background jobs of order transmission: dispatch, crash recovery, and status
 * polling (ADR-0052 §§2–3, CAP-320 #1226/#1318).
 *
 * <h2>Recovery runs on a timer, not only at startup</h2>
 *
 * The obvious place for "handle rows left {@code DISPATCHING} by a crash" is an
 * {@code ApplicationRunner}. On a timer it also covers the cases a startup hook misses: an
 * instance that died while its peers stayed up, a reconciliation whose status query failed and
 * needs another go, and a vendor that was unreachable at the moment we needed to ask. A
 * {@code DISPATCHING} row is the most dangerous state in this module — an order that may exist at
 * a vendor and is not yet recorded as existing — so it is checked continuously rather than once.
 *
 * <p>Recovery never re-dispatches. It only ever asks the vendor (ADR-0052 §3) or escalates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.supplier.order", name = "scheduler-enabled", matchIfMissing = true)
public class OrderTransmissionScheduler {

    /**
     * How many orders one status tick may ask about. Bounds a single tick's vendor traffic; the
     * per-order minimum interval bounds how often any one of them is asked.
     */
    private static final int STATUS_POLL_BATCH_SIZE = 100;

    private final SupplierTransmissionIntentRepository intentRepository;
    private final OrderTransmissionDispatcher dispatcher;
    private final OrderTransmissionReconciler reconciler;
    private final OrderStatusQueryRunner statusQueryRunner;
    private final TransmissionStateWriter stateWriter;
    private final Clock clock;

    /**
     * How long an intent may sit mid-flight before a human is asked to look. Bounded because the
     * alternative is an order that may exist at a vendor sitting unresolved indefinitely while
     * reconciliation quietly fails every cycle.
     */
    @Value("${pos.supplier.order.ambiguity-escalation-minutes:30}")
    private int ambiguityEscalationMinutes;

    /** Minimum gap between two status queries about the same order. */
    @Value("${pos.supplier.order.status-poll-min-interval-seconds:900}")
    private int statusPollMinIntervalSeconds;

    /**
     * Days of unchanged vendor answers after which polling stops (recorded as {@code STALE}, never
     * as a completion).
     */
    @Value("${pos.supplier.order.status-max-quiet-days:45}")
    private int statusMaxQuietDays;

    /** Sends orders waiting in {@code PENDING}, oldest first. */
    @Scheduled(fixedDelayString = "${pos.supplier.order.dispatch-poll-interval-ms:5000}")
    public void dispatchPending() {
        List<SupplierTransmissionIntentEntity> pending =
                intentRepository.findTop50ByAttemptStateOrderByTransmissionIntentIdAsc(
                        TransmissionAttemptState.PENDING);
        for (SupplierTransmissionIntentEntity intent : pending) {
            try {
                dispatcher.dispatch(intent);
            } catch (Exception e) {
                // One unreachable vendor must not stop another vendor's orders going out. The
                // intent stays where the dispatcher left it, which is never a state that re-sends.
                log.warn(
                        "Dispatch of transmission intent {} failed: {}",
                        intent.getTransmissionIntentId(),
                        e.toString(),
                        e);
            }
        }
    }

    /**
     * Resolves intents left mid-flight (ADR-0052 §2).
     *
     * <p>Each is reconciled through order status; one that has been mid-flight longer than the
     * escalation window goes to {@code MANUAL_REVIEW} instead of being asked about forever.
     */
    @Scheduled(fixedDelayString = "${pos.supplier.order.recovery-poll-interval-ms:60000}")
    public void recoverInFlight() {
        Instant now = Instant.now(clock);
        for (SupplierTransmissionIntentEntity intent :
                intentRepository.findByAttemptState(TransmissionAttemptState.DISPATCHING)) {
            try {
                if (isOlderThan(intent.getDispatchedAt(), now, Duration.ofMinutes(ambiguityEscalationMinutes))) {
                    stateWriter.recordManualReview(
                            intent.getTransmissionIntentId(),
                            "the outcome of this transmission has been unresolved for more than "
                                    + ambiguityEscalationMinutes
                                    + " minutes and the vendor could not confirm whether it holds document "
                                    + intent.getDocumentId() + "; a re-send would risk a duplicate (ADR-0052 §3)");
                    continue;
                }
                reconciler.reconcile(
                        intent.getTransmissionIntentId(),
                        "recovered mid-flight: the request may have reached the vendor before this instance"
                                + " stopped");
            } catch (Exception e) {
                log.warn(
                        "Recovery of transmission intent {} failed: {}",
                        intent.getTransmissionIntentId(),
                        e.toString(),
                        e);
            }
        }
    }

    /**
     * Asks vendors what has been scheduled and despatched, and publishes the changes
     * (#1318).
     *
     * <p>Runs for every order the vendor is still fulfilling — well past confirmation, because
     * confirmation is where the delivery story starts. Each order is asked about at most once per
     * {@code status-poll-min-interval-seconds}, so a short scheduler tick does not turn into
     * vendor traffic.
     */
    @Scheduled(fixedDelayString = "${pos.supplier.order.status-poll-interval-ms:60000}")
    public void pollOrderStatus() {
        Instant now = Instant.now(clock);
        for (SupplierTransmissionIntentEntity intent :
                intentRepository.findDueForStatusPolling(PageRequest.of(0, STATUS_POLL_BATCH_SIZE))) {
            try {
                if (!isOlderThan(intent.getLastPolledAt(), now, Duration.ofSeconds(statusPollMinIntervalSeconds))) {
                    continue;
                }
                OrderStatusQueryRunner.Answer answer = statusQueryRunner.query(intent);
                if (!answer.isAvailable()) {
                    // Nothing is recorded from a failed query. An unreachable vendor must never
                    // look like a vendor that despatched nothing.
                    log.debug(
                            "Order status unavailable for document {}: {}",
                            intent.getDocumentId(),
                            answer.unavailableReason());
                    continue;
                }
                stateWriter.recordStatusObservation(
                        intent.getTransmissionIntentId(), answer.result(), statusMaxQuietDays);
            } catch (Exception e) {
                log.warn(
                        "Order status poll for transmission intent {} failed: {}",
                        intent.getTransmissionIntentId(),
                        e.toString(),
                        e);
            }
        }
    }

    /**
     * Whether an instant is absent or older than a window.
     *
     * <p>An absent timestamp counts as "due": an order that has never been polled is the one most
     * in need of it.
     */
    private static boolean isOlderThan(Instant instant, Instant now, Duration window) {
        return instant == null || instant.plus(window).isBefore(now);
    }
}
