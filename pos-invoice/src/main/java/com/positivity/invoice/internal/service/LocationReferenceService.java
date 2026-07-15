package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.entity.ExtLocationReplica;
import com.positivity.invoice.internal.repository.ExtLocationReplicaRepository;
import com.positivity.tax.common.dto.TaxCalculationRequest.TaxAddress;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the shop-location address used as the tax jurisdiction (the place where the sale is
 * made) for invoice tax calculation, served from the {@code ext_location} replica (ADR-0044 §6,
 * #892). Replaces the retired synchronous {@code LocationServiceClient}; tax flows still run
 * ADR-0021 address validation — country and postal code are required for jurisdiction
 * determination — now against replica data.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocationReferenceService {

    private final ExtLocationReplicaRepository extLocationReplicaRepository;

    /**
     * Fetch the location's replica address and map it to a tax destination address.
     *
     * @param locationId the shop location backing the invoice
     * @return the destination address for tax calculation
     * @throws IllegalStateException if the location is not in the replica or lacks the
     *     country/postal code required for tax jurisdiction determination
     */
    @NonNull
    public TaxAddress resolveTaxAddress(@NonNull UUID locationId) {
        ExtLocationReplica location = extLocationReplicaRepository
                .findById(locationId)
                .orElseThrow(() -> new IllegalStateException("No location replica row for locationId " + locationId
                        + " — verify the location.events.v1 feed is consumed"));

        String country = trimToNull(location.getCountry());
        String postalCode = trimToNull(location.getPostalCode());
        if (country == null || postalCode == null) {
            throw new IllegalStateException("Location " + locationId
                    + " is missing country/postalCode required for tax jurisdiction determination");
        }

        return TaxAddress.builder()
                .countryCode(country)
                .regionCode(trimToNull(location.getRegion()))
                .city(trimToNull(location.getCity()))
                .postalCode(postalCode)
                .line1(trimToNull(location.getAddressLine1()))
                .line2(trimToNull(location.getAddressLine2()))
                .build();
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
