package com.positivity.shopmanager.internal.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shopmanager.internal.entity.OverrideRecord;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.OverrideRecordRepository;
import com.positivity.shopmanager.service.ConflictOverrideService;
import com.positivity.shopmanager.service.dto.ConflictOverrideRequest;
import com.positivity.shopmanager.service.dto.ConflictOverrideResponse;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements conflict override logic: flags the appointment, persists the
 * override audit
 * record, and returns a response. Caller must hold canonical schedule-editing
 * authority — enforced by {@code @PreAuthorize} on the service interface
 * (AC-2).
 */
@Service
@RequiredArgsConstructor
public class ConflictOverrideServiceImpl implements ConflictOverrideService {

    private static final String SYSTEM = "system";

    private final AppointmentRepository appointmentRepository;
    private final OverrideRecordRepository overrideRecordRepository;
    private final Clock clock;

    @Override
    @Transactional
    public @NonNull ConflictOverrideResponse execute(@NonNull ConflictOverrideRequest request) {
        // AC3: Override reason must not be blank
        if (request.getOverrideReason() == null || request.getOverrideReason().isBlank()) {
            throw new IllegalArgumentException("Override reason must not be blank");
        }

        // Locate the appointment — required for flagging and record linkage
        var appointment = appointmentRepository.findById(request.getAppointmentId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Appointment not found: " + request.getAppointmentId()));

        // AC4: Flag appointment as a conflict override
        appointment.setConflictOverride(true);
        appointmentRepository.save(appointment);

        // AC1: Persist the override audit record with timestamp from clock
        String actorId = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
        OverrideRecord record = OverrideRecord.builder()
                .appointment(appointment)
                .overriddenByUserId(actorId)
                .overrideTimestamp(Instant.now(clock))
                .overrideReason(request.getOverrideReason())
                .conflictDetails(request.getConflictDetails())
                .build();
        OverrideRecord saved = overrideRecordRepository.save(record);

        return ConflictOverrideResponse.builder()
                .overrideId(saved.getOverrideId())
                .appointmentId(request.getAppointmentId())
                .overriddenByUserId(actorId)
                .overrideTimestamp(saved.getOverrideTimestamp())
                .overrideReason(request.getOverrideReason())
                .build();
    }
}
