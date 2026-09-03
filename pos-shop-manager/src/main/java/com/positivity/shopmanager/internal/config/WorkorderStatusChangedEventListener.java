package com.positivity.shopmanager.internal.config;

import com.positivity.shopmanager.internal.dto.WorkorderStatusChangedEvent;
import com.positivity.shopmanager.internal.service.WorkorderStatusEventService;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges the in-process {@link WorkorderStatusChangedEvent} onto the appointment status sync.
 *
 * <p>The event is raised by the {@code workorder.events.v1} replica consumer, whose Kafka listener
 * method is itself {@code @Transactional} and writes both the {@code ext_workorder} row and the
 * {@code processed_events} idempotency row in that transaction. Running the appointment sync
 * inline inside it — the pre-#1658-review shape — coupled the two writes in the worst possible
 * way: an exception from the sync was caught and logged by the consumer's catch-all, but the
 * transaction was already marked rollback-only, so the {@code processed_events} insert failed at
 * commit. The replica update was lost <em>and</em> the record was redelivered forever, which is a
 * poison-message loop rather than a degraded write.
 *
 * <p>So the sync now runs {@link TransactionPhase#AFTER_COMMIT}, the same phase
 * {@code AppointmentEventListener} already uses for this module's other in-process notifications.
 * The replica write and its dedup row commit first and stay committed; the sync then runs in its
 * own transaction ({@code REQUIRES_NEW} on the service) and a failure in it is logged and
 * swallowed here rather than propagated back into the commit path. That is deliberately fail-open:
 * the appointment timeline is a downstream projection of the workorder fact, and losing one
 * timeline entry is recoverable, whereas losing the replica write and jamming the partition is
 * not. Kafka's own bounded retry with dead-lettering ({@code KafkaErrorHandlingConfig}, five
 * attempts then {@code {topic}.dlq}) still covers everything that fails <em>before</em> commit.
 *
 * <p>{@code fallbackExecution} keeps the listener working when the event is published outside any
 * transaction — a direct call, or a test — instead of silently dropping it.
 */
@Slf4j
@Component
public class WorkorderStatusChangedEventListener {

    private final WorkorderStatusEventService service;

    public WorkorderStatusChangedEventListener(@NonNull WorkorderStatusEventService service) {
        this.service = service;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWorkorderStatusChanged(WorkorderStatusChangedEvent event) {
        try {
            service.handleWorkorderStatusChanged(event);
        } catch (Exception e) {
            log.error(
                    "Appointment status sync failed for workorderId={} eventId={}; the workorder replica is "
                            + "already committed and the event stays processed, so the message is not redelivered",
                    event.workorderId(),
                    event.eventId(),
                    e);
        }
    }
}
