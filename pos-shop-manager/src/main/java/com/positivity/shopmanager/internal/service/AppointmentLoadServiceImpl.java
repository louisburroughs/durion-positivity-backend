package com.positivity.shopmanager.internal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.positivity.shopmanager.internal.dto.AppointmentCreateModel;
import com.positivity.shopmanager.service.AppointmentLoadService;
import java.util.UUID;

@Slf4j
@Service
public class AppointmentLoadServiceImpl implements AppointmentLoadService {

    @Override
    public AppointmentCreateModel loadCreateModel(String sourceType, String sourceId, UUID facilityId,
            UUID correlationId) {
        log.debug("Loading create model: sourceType={}, sourceId={}, facilityId={}", sourceType, sourceId, facilityId);
        return new AppointmentCreateModel();
    }

    @Override
    public String getFacilityTimeZoneId(UUID facilityId) {
        log.debug("Getting facility timezone: facilityId={}", facilityId);
        return "UTC";
    }
}
