package com.positivity.shopmanager.internal.event;

import com.positivity.shopmanager.internal.enums.RescheduleReasonCode;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event emitted when an appointment is rescheduled.
 *
 * <p>
 * CAP-249 Story #11: extended with mandatory reschedule reason, rescheduledAt,
 * newEndAt, optional estimateId/workOrderId, and assignment status for
 * downstream
 * consumers (workexec, notification).
 */
public record AppointmentRescheduledEvent(
                UUID appointmentId,
                String workorderLinkRef,
                Instant previousStartAt,
                Instant previousEndAt,
                Instant newStartAt,
                Instant newEndAt,
                RescheduleReasonCode rescheduleReason,
                String actorId,
                Instant rescheduledAt,
                UUID estimateId,
                UUID workOrderId,
                String assignmentStatus) {
}
