package com.positivity.invoice.internal.client;

import com.positivity.tax.common.dto.TaxCalculationRequest.TaxAddress;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Resolves the shop-location address used as the tax jurisdiction (the place where
 * the sale is made) for invoice tax calculation.
 *
 * <p>Per the platform rule, other services must not replicate address data: they
 * store the {@code locationId} and query pos-location for the full address.
 */
@Component
public class LocationServiceClient {

    private static final Logger log = LoggerFactory.getLogger(LocationServiceClient.class);

    private final RestClient restClient;

    public LocationServiceClient(
            RestClient.Builder restClientBuilder,
            @Value("${invoice.location.base-url:http://pos-location:8080/v1/locations}") String locationBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(locationBaseUrl).build();
    }

    /**
     * Fetch the location's address and map it to a tax destination address.
     *
     * @param locationId the shop location backing the invoice
     * @return the destination address for tax calculation
     * @throws IllegalStateException if the location cannot be resolved or lacks the
     *     country/postal code required for tax jurisdiction determination
     */
    @NonNull
    public TaxAddress resolveTaxAddress(@NonNull UUID locationId) {
        // pos-location guards GET /v1/locations/{id} with @PreAuthorize('location:read').
        // Propagate the required authority via the gateway authorities header for this
        // service-to-service call (see GatewayAuthoritiesFilter and the tax client pattern).
        LocationAddressResponse location = restClient
                .get()
                .uri("/{locationId}", locationId)
                .header("X-User", "pos-invoice")
                .header("X-Authorities", "location:read")
                .retrieve()
                .body(LocationAddressResponse.class);

        if (location == null) {
            throw new IllegalStateException("pos-location returned no address for locationId " + locationId);
        }

        String country = trimToNull(location.getCountry());
        String postalCode = trimToNull(location.getPostalCode());
        if (country == null || postalCode == null) {
            throw new IllegalStateException("Location " + locationId
                    + " is missing country/postalCode required for tax jurisdiction determination");
        }

        return TaxAddress.builder()
                .countryCode(country)
                .regionCode(trimToNull(location.getState()))
                .city(trimToNull(location.getCity()))
                .postalCode(postalCode)
                .line1(trimToNull(location.getAddressLine1()))
                .line2(trimToNull(location.getAddressLine2()))
                .build();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
