package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.WorkorderStatusChangedEvent;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.StatusTimelineEntry;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.WorkOrderAppointmentMappingRepository;
import com.positivity.shopmanager.service.WorkorderStatusEventService;
import java.util.ArrayList;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkorderStatusEventServiceImpl implements WorkorderStatusEventService {

    private static final Logger log = LoggerFactory.getLogger(WorkorderStatusEventServiceImpl.class);

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

    @Override
    @Transactional
    public void handleWorkorderStatusChanged(@NonNull WorkorderStatusChangedEvent event) {
        AppointmentStatus mappedStatus = STATUS_MAPPING.get(event.newStatus());
        if (mappedStatus == null) {
            log.warn(
                    "Unknown workexec status '{}' in event {}, skipping",
                    sanitizeForLog(event.newStatus()),
                    event.eventId());
            return;
        }

        var mappingOpt = mappingRepository.findByWorkOrderId(event.workOrderId());
        if (mappingOpt.isEmpty()) {
            log.warn(
                    "Orphaned Work Order: no appointment mapping found for workOrderId={}, eventId={}",
                    event.workOrderId(),
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
