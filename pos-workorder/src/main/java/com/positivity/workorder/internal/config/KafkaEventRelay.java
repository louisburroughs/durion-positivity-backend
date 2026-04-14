package com.positivity.workorder.internal.config;

import com.positivity.workorder.internal.domain.TimeEntryApprovedEvent;
import com.positivity.workorder.internal.domain.TimeEntryRejectedEvent;
import com.positivity.workorder.internal.domain.TravelSegmentStartedEvent;
import com.positivity.workorder.internal.domain.TravelSegmentStoppedEvent;
import com.positivity.workorder.internal.domain.WorkSessionStartedEvent;
import com.positivity.workorder.internal.domain.WorkSessionStoppedEvent;
import com.positivity.workorder.internal.event.EstimateCreatedEvent;
import com.positivity.workorder.internal.event.EstimateRevisedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Relays committed domain events to Kafka.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class KafkaEventRelay {

    private final KafkaProducer producer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWorkSessionStarted(WorkSessionStartedEvent event) {
        producer.publish(
                "workorder.work_session.started.v1", event.workSessionId().toString(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWorkSessionStopped(WorkSessionStoppedEvent event) {
        producer.publish(
                "workorder.work_session.stopped.v1", event.workSessionId().toString(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTravelSegmentStarted(TravelSegmentStartedEvent event) {
        producer.publish(
                "workorder.travel_segment.started.v1", event.travelSegmentId().toString(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTravelSegmentStopped(TravelSegmentStoppedEvent event) {
        producer.publish(
                "workorder.travel_segment.stopped.v1", event.travelSegmentId().toString(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTimeEntryApproved(TimeEntryApprovedEvent event) {
        producer.publish("workorder.time_entry.approved.v1", event.timeEntryId().toString(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTimeEntryRejected(TimeEntryRejectedEvent event) {
        producer.publish("workorder.time_entry.rejected.v1", event.timeEntryId().toString(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEstimateCreated(EstimateCreatedEvent event) {
        producer.publish("workorder.estimate.created.v1", event.getEstimateId().toString(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEstimateRevised(EstimateRevisedEvent event) {
        if (event.getWorkorderId() == null) {
            log.debug(
                    "Skipping Kafka estimate.revised publish due to null workorderId for estimateId={}",
                    event.getEstimateId());
            return;
        }
        producer.publish("workorder.estimate.revised.v1", event.getWorkorderId().toString(), event);
    }
}
