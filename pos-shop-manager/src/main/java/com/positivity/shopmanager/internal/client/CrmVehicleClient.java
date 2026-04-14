package com.positivity.shopmanager.internal.client;

import com.positivity.shopmanager.internal.exception.CrmVehicleNotFoundException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class CrmVehicleClient {

    private final RestClient crmRestClient;

    @Value("${pos.crm.vehicle-base-url:http://localhost:8080/v1/vehicles}")
    private String vehicleBaseUrl;

    public CrmVehicleClient(@Qualifier("crmRestClient") RestClient crmRestClient) {
        this.crmRestClient = crmRestClient;
    }

    public @NonNull Map<String, Object> getVehicleById(@NonNull UUID vehicleId) {
        try {
            Map<?, ?> payload = crmRestClient
                    .get()
                    .uri(vehicleBaseUrl + "/{vehicleId}", vehicleId)
                    .retrieve()
                    .body(Map.class);
            if (payload == null) {
                return Collections.emptyMap();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typedPayload = (Map<String, Object>) payload;
            return typedPayload;
        } catch (HttpClientErrorException.NotFound exception) {
            throw new CrmVehicleNotFoundException(vehicleId);
        }
    }
}
