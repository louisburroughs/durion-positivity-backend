package com.positivity.shopManager.service.impl;

import com.positivity.shopManager.internal.dto.AppointmentCreateModel;
import com.positivity.shopManager.service.AppointmentLoadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
