package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.WorkorderStatusChangedEvent;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.StatusTimelineEntry;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.WorkOrderAppointmentMappingRepository;
import java.util.ArrayList;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkorderStatusEventServiceImpl implements WorkorderStatusEventService {

    private static final Logger log = LoggerFactory.getLogger(WorkorderStatusEventServiceImpl.class);

    /**
     * {@code "COMPLETED" -> QUALITY_CHECK} is deliberate, not a mapping bug (#2021 F7/AC9,
     * confirmed against the locked {@code handleWorkorderStatusChanged_completedStatus_mapsToQualityCheck}
     * test below, itself "per spec" for CAP-140 #63 AC5). Workexec's {@code COMPLETED} means the
     * labour is finished, not that the vehicle is ready to leave: the appointment still needs a
     * quality check before it can be marked {@code READY_FOR_PICKUP}, the same gate
     * {@code AWAITING_APPROVAL} maps to. {@link AppointmentStatus#COMPLETED} is therefore never
     * reached from this feed by design — it names a shopmgmt-owned event (the customer has
     * collected the vehicle) that workexec's lifecycle has no equivalent for and does not publish;
     * nothing in {@code STATUS_MAPPING} should ever produce it. #2021's new {@code actualEndAt}
     * (sourced from the workorder's {@code completedAt}, not from the appointment's own status)
     * is what a caller reads to know the job is actually done — it does not depend on, and must
     * not be confused with, the appointment reaching {@code QUALITY_CHECK} here.
     */
    private static final Map<String, AppointmentStatus> STATUS_MAPPING = Map.of(
            "DRAFT", AppointmentStatus.SCHEDULED,
            "ASSIGNED", AppointmentStatus.CHECKED_IN,
            "WORK_IN_PROGRESS", AppointmentStatus.WORK_IN_PROGRESS,
            "AWAITING_PARTS", AppointmentStatus.WAITING_FOR_PARTS,
            "AWAITING_APPROVAL", AppointmentStatus.QUALITY_CHECK,
            "READY_FOR_PICKUP", AppointmentStatus.READY_FOR_PICKUP,
            "COMPLETED", AppointmentStatus.QUALITY_CHECK,
            "INVOICED", AppointmentStatus.INVOICED,
            "CANCELLED", AppointmentStatus.CANCELLED,
            "REOPENED", AppointmentStatus.REOPENED);

    private final WorkOrderAppointmentMappingRepository mappingRepository;
    private final AppointmentRepository appointmentRepository;

    public WorkorderStatusEventServiceImpl(
            @NonNull WorkOrderAppointmentMappingRepository mappingRepository,
            @NonNull AppointmentRepository appointmentRepository) {
        this.mappingRepository = mappingRepository;
        this.appointmentRepository = appointmentRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code REQUIRES_NEW} is load-bearing, not decorative: the caller is a
     * {@code TransactionPhase.AFTER_COMMIT} listener, and a {@code REQUIRED} transaction joined
     * from an after-commit callback participates in a transaction that has already committed, so
     * the appointment write would never be flushed. A separate transaction also keeps a failure
     * here from touching the replica write that produced the event (#1658 review).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleWorkorderStatusChanged(@NonNull WorkorderStatusChangedEvent event) {
        AppointmentStatus mappedStatus = STATUS_MAPPING.get(event.newStatus());
        if (mappedStatus == null) {
            log.warn(
                    "Unknown workexec status '{}' in event {}, skipping",
                    sanitizeForLog(event.newStatus()),
                    event.eventId());
            return;
        }

        var mappingOpt = mappingRepository.findByWorkOrderId(event.workorderId());
        if (mappingOpt.isEmpty()) {
            log.warn(
                    "Orphaned Work Order: no appointment mapping found for workOrderId={}, eventId={}",
                    event.workorderId(),
                    event.eventId());
            return;
        }

        Appointment appointment = appointmentRepository
                .findById(mappingOpt.get().getAppointmentId())
                .orElseThrow(() -> new IllegalStateException(
                        "Appointment not found: " + mappingOpt.get().getAppointmentId()));

        if (appointment.getStatusTimeline() == null) {
            appointment.setStatusTimeline(new ArrayList<>());
        }

        boolean alreadyProcessed = appointment.getStatusTimeline().stream()
                .anyMatch(entry -> event.eventId().equals(entry.getSourceEventId()));
        if (alreadyProcessed) {
            log.debug("Event {} already processed, skipping", event.eventId());
            return;
        }

        appointment.setStatus(mappedStatus);

        StatusTimelineEntry timelineEntry = StatusTimelineEntry.builder()
                .status(mappedStatus)
                .changeTimestamp(event.eventTimestamp())
                .sourceEventId(event.eventId())
                .build();
        appointment.getStatusTimeline().add(timelineEntry);

        if (mappedStatus == AppointmentStatus.REOPENED) {
            appointment.setReopenFlag(true);
        }

        appointmentRepository.save(appointment);
    }

    private String sanitizeForLog(Object value) {
        if (value == null) {
            return "null";
        }
        return value.toString().replaceAll("[\\r\\n\\t]", " ");
    }
}
