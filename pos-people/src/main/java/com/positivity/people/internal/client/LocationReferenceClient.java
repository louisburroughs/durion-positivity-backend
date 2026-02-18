package com.positivity.people.internal.client;

import com.positivity.people.internal.exception.LocationNotFoundException;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class LocationReferenceClient {

    private final RestClient restClient;
    private final boolean validationEnabled;

    public LocationReferenceClient(
            @Qualifier("locationServiceRestClient") RestClient restClient,
            @Value("${pos.location.validation.enabled:true}") boolean validationEnabled) {
        this.restClient = restClient;
        this.validationEnabled = validationEnabled;
    }

    public void assertLocationExists(@NonNull UUID locationId) {
        if (!validationEnabled) {
            return;
        }

        try {
            restClient.get()
                    .uri("/v1/locations/{locationId}", locationId)
                    .retrieve()
                    .onStatus(statusCode -> statusCode.value() == 404,
                            (request, response) -> {
                                throw new LocationNotFoundException(locationId);
                            })
                    .onStatus(HttpStatusCode::is5xxServerError,
                            (request, response) -> {
                                throw new IllegalStateException("pos-location service is unavailable");
                            })
                    .toBodilessEntity();
        } catch (LocationNotFoundException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to validate locationId against pos-location: " + locationId,
                    ex);
        }
    }
}
