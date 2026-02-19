package com.positivity.shopmanager.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.positivity.shopmanager.internal.dto.AppointmentCreateModel;
import com.positivity.shopmanager.service.AppointmentLoadService;

@Slf4j
@Service
public class AppointmentLoadServiceImpl implements AppointmentLoadService {

    @Override
    public AppointmentCreateModel loadCreateModel(String sourceType, String sourceId, String facilityId,
            String correlationId) {
        log.debug("Loading create model: sourceType={}, sourceId={}, facilityId={}", sourceType, sourceId, facilityId);
        return new AppointmentCreateModel();
    }

    @Override
    public String getFacilityTimeZoneId(String facilityId) {
        log.debug("Getting facility timezone: facilityId={}", facilityId);
        return "UTC";
    }
}
