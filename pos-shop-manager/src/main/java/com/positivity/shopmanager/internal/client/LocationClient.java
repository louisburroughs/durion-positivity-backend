package com.positivity.shopmanager.internal.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LocationClient {
    private final RestClient restClient;
    private final String locationServiceUrl;

    public LocationClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
            @Value("${location.service.url:http://api-gateway}") String locationServiceUrl) {
        this.restClient = builder.build();
        this.locationServiceUrl = locationServiceUrl;
    }

    public Object getBays() {
        return restClient
                .get()
                .uri(locationServiceUrl + "/location/v1/locations/bays")
                .retrieve()
                .body(Object.class);
    }

    public Object getBayById(Long locationId, Long bayId) {
        return restClient
                .get()
                .uri(locationServiceUrl + "/location/v1/locations/{locationId}/bays/{bayId}", locationId, bayId)
                .retrieve()
                .body(Object.class);
    }

    public Object createBay(Long locationId, Object request) {
        return restClient
                .post()
                .uri(locationServiceUrl + "/location/v1/locations/{locationId}/bays", locationId)
                .body(request)
                .retrieve()
                .body(Object.class);
    }

    public Object updateBays(Object request) {
        restClient
                .put()
                .uri(locationServiceUrl + "/location/v1/locations/bays")
                .body(request)
                .retrieve();
        return request;
    }

    public void deleteBay(Long locationId, Long bayId) {
        restClient
                .delete()
                .uri(locationServiceUrl + "/location/v1/locations/{locationId}/bays/{bayId}", locationId, bayId)
                .retrieve();
    }

    public Object getMobileUnits() {
        return restClient
                .get()
                .uri(locationServiceUrl + "/location/v1/locations/mobileUnit")
                .retrieve()
                .body(Object.class);
    }

    public Object getMobileUnitById(Long locationId, Long bayId) {
        return restClient
                .get()
                .uri(locationServiceUrl + "/location/v1/locations/{locationId}/mobileUnit/{bayId}", locationId, bayId)
                .retrieve()
                .body(Object.class);
    }

    public Object createMobileUnit(Long locationId, Object request) {
        return restClient
                .post()
                .uri(locationServiceUrl + "/location/v1/locations/{locationId}/mobileUnit", locationId)
                .body(request)
                .retrieve()
                .body(Object.class);
    }

    public Object updateMobileUnits(Object request) {
        restClient
                .put()
                .uri(locationServiceUrl + "/location/v1/locations/mobileUnit")
                .body(request)
                .retrieve();
        return request;
    }

    public void deleteMobileUnit(Long locationId, Long bayId) {
        restClient
                .delete()
                .uri(locationServiceUrl + "/location/v1/locations/{locationId}/mobileUnit/{bayId}", locationId, bayId)
                .retrieve();
    }
}
