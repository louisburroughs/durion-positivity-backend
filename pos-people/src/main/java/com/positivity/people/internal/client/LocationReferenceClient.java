package com.positivity.people.internal.client;

import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.positivity.people.internal.client.dto.LocationValidationResponse;

@Component
public class LocationReferenceClient {

    private final RestClient restClient;

    public LocationReferenceClient(@Qualifier("locationServiceRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public boolean isLocationActive(@NonNull UUID locationId) {
        try {
            LocationValidationResponse response = restClient.get()
                    .uri("/v1/locations/{locationId}/validation", locationId)
                    .retrieve()
                    .body(LocationValidationResponse.class);
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Location validation returned empty response for " + locationId);
            }
            return response.exists() && response.active();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return false;
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Unable to validate location " + locationId, ex);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Unable to validate location " + locationId, ex);
        }
    }
}
