package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.LocationProfile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for the accounting-side location dimension (issue #731). */
public interface LocationProfileRepository extends JpaRepository<LocationProfile, UUID> {

    /**
     * Find the profile for an accounting {@code locationId} dimension value.
     *
     * @param locationCode the location code (e.g. {@code LOC-107})
     * @return the profile, or empty when the location has no accounting-side master data
     */
    Optional<LocationProfile> findByLocationCode(@NonNull String locationCode);

    /**
     * Batch form of {@link #findByLocationCode} for display resolution (issue #1778): one
     * {@code IN} query for every location referenced by a response, rather than one per
     * reference.
     *
     * @param locationCodes the location codes to look up
     * @return the profiles that exist; codes with no accounting-side master data are simply absent
     */
    @NonNull
    List<LocationProfile> findByLocationCodeIn(@NonNull Collection<String> locationCodes);
}
