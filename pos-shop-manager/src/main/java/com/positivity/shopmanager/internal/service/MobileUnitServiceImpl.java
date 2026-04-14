package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.client.LocationClient;
import com.positivity.shopmanager.service.MobileUnitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Slf4j
@Service
public class MobileUnitServiceImpl implements MobileUnitService {

    private final LocationClient locationClient;

    @Override
    public Object getMobileUnits(Long locationId, Long bayId) {
        if (locationId != null && bayId != null) {
            log.info("Fetching mobile unit ID: {} for location ID: {}", bayId, locationId);
            return locationClient.getMobileUnitById(locationId, bayId);
        } else {
            log.info("Fetching all mobile units");
            return locationClient.getMobileUnits();
        }
    }

    @Override
    public Object createMobileUnit(Long locationId, Object request) {
        log.info("Creating mobile unit for location ID: {}", locationId);
        return locationClient.createMobileUnit(locationId, request);
    }

    @Override
    public Object manageMobileUnits(Object request) {
        log.info("Managing mobile units in bulk");
        return locationClient.updateMobileUnits(request);
    }

    @Override
    public void deleteMobileUnit(Long locationId, Long bayId) {
        log.info("Deleting mobile unit ID: {} for location ID: {}", bayId, locationId);
        locationClient.deleteMobileUnit(locationId, bayId);
    }
}
