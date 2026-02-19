package com.positivity.shopmanager.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.positivity.shopmanager.internal.client.LocationClient;

@RequiredArgsConstructor
@Slf4j
@Service
public class BayService {

    private final LocationClient locationClient;

    public Object getBays(Long locationId, Long bayId) {
        if (locationId != null && bayId != null) {
            log.info("Fetching bay ID: {} for location ID: {}", bayId, locationId);
            return locationClient.getBayById(locationId, bayId);
        } else {
            log.info("Fetching all bays");
            return locationClient.getBays();
        }
    }

    public Object createBay(Long locationId, Object request) {
        log.info("Creating bay for location ID: {}", locationId);
        return locationClient.createBay(locationId, request);
    }

    public Object manageBays(Object request) {
        log.info("Managing bays in bulk");
        return locationClient.updateBays(request);
    }

    public void deleteBay(Long locationId, Long bayId) {
        log.info("Deleting bay ID: {} for location ID: {}", bayId, locationId);
        locationClient.deleteBay(locationId, bayId);
    }
}
