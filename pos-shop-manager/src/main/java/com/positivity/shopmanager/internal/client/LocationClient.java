package com.positivity.shopmanager.internal.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LocationClient {

    private static final String AUTH_BAY_READ = "location:bay:read";
    private static final String AUTH_BAY_MANAGE = "location:bay:manage";
    private static final String AUTH_MOBILE_UNIT_READ = "location:mobile-unit:read";
    private static final String AUTH_MOBILE_UNIT_MANAGE = "location:mobile-unit:manage";

    private final RestClient restClient;

    public LocationClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
            @Value("${pos.location.service-id:location}") String serviceId) {
        this.restClient = builder.baseUrl("http://" + serviceId)
                .defaultHeader("X-User", "pos-shop-manager")
                .build();
    }

    public Object getBays() {
        return restClient
                .get()
                .uri("/v1/locations/bays")
                .header("X-Authorities", AUTH_BAY_READ)
                .retrieve()
                .body(Object.class);
    }

    public Object getBayById(Long locationId, Long bayId) {
        return restClient
                .get()
                .uri("/v1/locations/{locationId}/bays/{bayId}", locationId, bayId)
                .header("X-Authorities", AUTH_BAY_READ)
                .retrieve()
                .body(Object.class);
    }

    public Object createBay(Long locationId, Object request) {
        return restClient
                .post()
                .uri("/v1/locations/{locationId}/bays", locationId)
                .header("X-Authorities", AUTH_BAY_MANAGE)
                .body(request)
                .retrieve()
                .body(Object.class);
    }

    public Object updateBays(Object request) {
        restClient
                .put()
                .uri("/v1/locations/bays")
                .header("X-Authorities", AUTH_BAY_MANAGE)
                .body(request)
                .retrieve();
        return request;
    }

    public void deleteBay(Long locationId, Long bayId) {
        restClient
                .delete()
                .uri("/v1/locations/{locationId}/bays/{bayId}", locationId, bayId)
                .header("X-Authorities", AUTH_BAY_MANAGE)
                .retrieve();
    }

    public Object getMobileUnits() {
        return restClient
                .get()
                .uri("/v1/locations/mobileUnit")
                .header("X-Authorities", AUTH_MOBILE_UNIT_READ)
                .retrieve()
                .body(Object.class);
    }

    public Object getMobileUnitById(Long locationId, Long bayId) {
        return restClient
                .get()
                .uri("/v1/locations/{locationId}/mobileUnit/{bayId}", locationId, bayId)
                .header("X-Authorities", AUTH_MOBILE_UNIT_READ)
                .retrieve()
                .body(Object.class);
    }

    public Object createMobileUnit(Long locationId, Object request) {
        return restClient
                .post()
                .uri("/v1/locations/{locationId}/mobileUnit", locationId)
                .header("X-Authorities", AUTH_MOBILE_UNIT_MANAGE)
                .body(request)
                .retrieve()
                .body(Object.class);
    }

    public Object updateMobileUnits(Object request) {
        restClient
                .put()
                .uri("/v1/locations/mobileUnit")
                .header("X-Authorities", AUTH_MOBILE_UNIT_MANAGE)
                .body(request)
                .retrieve();
        return request;
    }

    public void deleteMobileUnit(Long locationId, Long bayId) {
        restClient
                .delete()
                .uri("/v1/locations/{locationId}/mobileUnit/{bayId}", locationId, bayId)
                .header("X-Authorities", AUTH_MOBILE_UNIT_MANAGE)
                .retrieve();
    }
}
