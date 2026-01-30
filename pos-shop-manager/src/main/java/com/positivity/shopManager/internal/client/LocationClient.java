package com.positivity.shopManager.internal.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LocationClient {
    private final RestClient restClient;

    @Value("${location.service.url:http://localhost:8084}")
    private String locationServiceUrl;

    public LocationClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public Object getBays() {
        return restClient.get()
                .uri(locationServiceUrl + "/v1/locations/bays")
                .retrieve()
                .body(Object.class);
    }

    public Object getBayById(Long locationId, Long bayId) {
        return restClient.get()
                .uri(locationServiceUrl + "/v1/locations/{locationId}/bays/{bayId}", locationId, bayId)
                .retrieve()
                .body(Object.class);
    }

    public Object createBay(Long locationId, Object request) {
        return restClient.post()
                .uri(locationServiceUrl + "/v1/locations/{locationId}/bays", locationId)
                .body(request)
                .retrieve()
                .body(Object.class);
    }

    public Object updateBays(Object request) {
        restClient.put()
                .uri(locationServiceUrl + "/v1/locations/bays")
                .body(request)
                .retrieve();
        return request;
    }

    public void deleteBay(Long locationId, Long bayId) {
        restClient.delete()
                .uri(locationServiceUrl + "/v1/locations/{locationId}/bays/{bayId}", locationId, bayId)
                .retrieve();
    }

    public Object getMobileUnits() {
        return restClient.get()
                .uri(locationServiceUrl + "/v1/locations/mobileUnit")
                .retrieve()
                .body(Object.class);
    }

    public Object getMobileUnitById(Long locationId, Long bayId) {
        return restClient.get()
                .uri(locationServiceUrl + "/v1/locations/{locationId}/mobileUnit/{bayId}", locationId, bayId)
                .retrieve()
                .body(Object.class);
    }

    public Object createMobileUnit(Long locationId, Object request) {
        return restClient.post()
                .uri(locationServiceUrl + "/v1/locations/{locationId}/mobileUnit", locationId)
                .body(request)
                .retrieve()
                .body(Object.class);
    }

    public Object updateMobileUnits(Object request) {
        restClient.put()
                .uri(locationServiceUrl + "/v1/locations/mobileUnit")
                .body(request)
                .retrieve();
        return request;
    }

    public void deleteMobileUnit(Long locationId, Long bayId) {
        restClient.delete()
                .uri(locationServiceUrl + "/v1/locations/{locationId}/mobileUnit/{bayId}", locationId, bayId)
                .retrieve();
    }
}
