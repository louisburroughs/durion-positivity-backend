package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.WorkOrderAppointmentMapping;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.WorkOrderAppointmentMappingRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkorderAppointmentLinkServiceImpl implements WorkorderAppointmentLinkService {

    private final WorkOrderAppointmentMappingRepository mappingRepository;
    private final AppointmentRepository appointmentRepository;

    @Override
    public boolean link(@NonNull UUID workorderId, @NonNull UUID appointmentId) {
        if (mappingRepository.existsById(workorderId)) {
            return false;
        }
        Appointment appointment = appointmentRepository.findById(appointmentId).orElse(null);
        if (appointment == null) {
            // Not an error to retry: the appointment is this module's own row, so if it is not here
            // now a redelivery will not find it either. The next fact for the workorder tries again.
            log.warn(
                    "Workorder {} names source appointment {}, which this module does not hold; not linked",
                    workorderId,
                    appointmentId);
            return false;
        }
        mappingRepository.save(WorkOrderAppointmentMapping.builder()
                .workOrderId(workorderId)
                .appointment(appointment)
                .build());
        return true;
    }
}
