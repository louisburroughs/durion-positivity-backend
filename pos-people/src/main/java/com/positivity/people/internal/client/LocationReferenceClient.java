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
import com.positivity.people.internal.client.dto.LocationSummaryResponse;

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
		}
		catch (RestClientResponseException ex) {
			if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
				return false;
			}
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Unable to validate location " + locationId, ex);
		}
		catch (Exception ex) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Unable to validate location " + locationId, ex);
		}
	}

	/**
	 * Resolve a location display name from the location service.
	 * @param locationId location identifier
	 * @return location display name when available
	 */
	public String getLocationName(@NonNull UUID locationId) {
		try {
			LocationSummaryResponse response = restClient.get()
				.uri("/v1/locations/{locationId}", locationId)
				.retrieve()
				.body(LocationSummaryResponse.class);

			if (response == null || response.name() == null || response.name().isBlank()) {
				return locationId.toString();
			}
			return response.name();
		}
		catch (RestClientResponseException ex) {
			if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown location: " + locationId);
			}
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to fetch location " + locationId,
					ex);
		}
		catch (ResponseStatusException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to fetch location " + locationId,
					ex);
		}
	}

}
