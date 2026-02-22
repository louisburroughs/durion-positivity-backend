package com.positivity.shopmanager.internal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
import com.positivity.shopmanager.internal.dto.ConflictResponse;
import com.positivity.shopmanager.internal.exception.SchedulingConflictException;
import com.positivity.shopmanager.service.ConflictDetectionService;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ConflictDetectionServiceImpl implements ConflictDetectionService {

    @Override
    public void detectConflicts(AppointmentCreateRequest request, String correlationId)
            throws SchedulingConflictException {
        log.debug("Detecting conflicts for appointment: correlationId={}", correlationId);
    }

    @Override
    public boolean isWithinOperatingHours(String facilityId, String scheduledStartDateTime, String scheduledEndDateTime,
            String facilityTimeZoneId) {
        log.debug("Checking operating hours: facilityId={}", facilityId);
        return true;
    }

    @Override
    public List<ConflictResponse.Conflict> checkMechanicAvailability(String facilityId,
            String scheduledStartDateTime, String scheduledEndDateTime) {
        log.debug("Checking mechanic availability: facilityId={}", facilityId);
        return new ArrayList<>();
    }

    @Override
    public List<ConflictResponse.Conflict> checkBayAvailability(String facilityId, String scheduledStartDateTime,
            String scheduledEndDateTime) {
        log.debug("Checking bay availability: facilityId={}", facilityId);
        return new ArrayList<>();
    }
}
