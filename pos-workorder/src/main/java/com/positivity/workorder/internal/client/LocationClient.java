package com.positivity.workorder.internal.client;

import com.positivity.tax.common.dto.TaxCalculationRequest.TaxAddress;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Resolves the shop-location address used as the tax jurisdiction (the place where
 * the sale is made) for estimate tax calculation.
 *
 * <p>Per the platform rule, other services must not replicate address data: they
 * store the {@code locationId} and query pos-location for the full address.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LocationClient {

    private final RestClient locationServiceRestClient;

    /**
     * Fetch the location's address and map it to a tax destination address.
     *
     * @param locationId the shop location backing the estimate
     * @return the destination address for tax calculation
     * @throws IllegalStateException if the location cannot be resolved or lacks the
     *     country/postal code required for tax jurisdiction determination
     */
    @NonNull
    public TaxAddress resolveTaxAddress(@NonNull UUID locationId) {
        // pos-location guards GET /v1/locations/{id} with @PreAuthorize('location:read').
        // Propagate the required authority via the gateway authorities header for this
        // service-to-service call (see the people/tax client patterns).
        LocationAddressResponse location = locationServiceRestClient
                .get()
                .uri("/v1/locations/{locationId}", locationId)
                .header("X-User", "pos-workorder")
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
