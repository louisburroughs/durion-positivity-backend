package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.LocationFxRate;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for configured location/fiscal-year FX rates (issue #731). */
public interface LocationFxRateRepository extends JpaRepository<LocationFxRate, UUID> {

    /**
     * Find the configured FX rate for a location and fiscal year.
     *
     * @param locationCode the accounting {@code locationId} dimension value
     * @param fiscalYear the fiscal year the rate applies to
     * @return the configured rate, or empty when the location defaults to a US plant (1.00)
     */
    Optional<LocationFxRate> findByLocationCodeAndFiscalYear(@NonNull String locationCode,
            int fiscalYear);
}
