package com.positivity.shopManager.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class LocationClient {
    private final RestTemplate restTemplate;

    @Value("${location.service.url:http://localhost:8084}")
    private String locationServiceUrl;

    public LocationClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Object getBays() {
        return restTemplate.getForObject(locationServiceUrl + "/v1/locations/bays", Object.class);
    }

    public Object getBayById(Long locationId, Long bayId) {
        return restTemplate.getForObject(
                locationServiceUrl + "/v1/locations/" + locationId + "/bays/" + bayId,
                Object.class);
    }

    public Object createBay(Long locationId, Object request) {
        return restTemplate.postForObject(
                locationServiceUrl + "/v1/locations/" + locationId + "/bays",
                request,
                Object.class);
    }

    public Object updateBays(Object request) {
        restTemplate.put(locationServiceUrl + "/v1/locations/bays", request);
        return request;
    }

    public void deleteBay(Long locationId, Long bayId) {
        restTemplate.delete(locationServiceUrl + "/v1/locations/" + locationId + "/bays/" + bayId);
    }

    public Object getMobileUnits() {
        return restTemplate.getForObject(locationServiceUrl + "/v1/locations/mobileUnit", Object.class);
    }

    public Object getMobileUnitById(Long locationId, Long bayId) {
        return restTemplate.getForObject(
                locationServiceUrl + "/v1/locations/" + locationId + "/mobileUnit/" + bayId,
                Object.class);
    }

    public Object createMobileUnit(Long locationId, Object request) {
        return restTemplate.postForObject(
                locationServiceUrl + "/v1/locations/" + locationId + "/mobileUnit",
                request,
                Object.class);
    }

    public Object updateMobileUnits(Object request) {
        restTemplate.put(locationServiceUrl + "/v1/locations/mobileUnit", request);
        return request;
    }

    public void deleteMobileUnit(Long locationId, Long bayId) {
        restTemplate.delete(locationServiceUrl + "/v1/locations/" + locationId + "/mobileUnit/" + bayId);
    }
}
