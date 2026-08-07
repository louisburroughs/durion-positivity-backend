package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.LocationProfile;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for the accounting-side location dimension (issue #731). */
@Repository
public interface LocationProfileRepository extends JpaRepository<LocationProfile, UUID> {

    /**
     * Find the profile for an accounting {@code locationId} dimension value.
     *
     * @param locationCode the location code (e.g. {@code LOC-107})
     * @return the profile, or empty when the location has no accounting-side master data
     */
    @NonNull
    Optional<LocationProfile> findByLocationCode(@NonNull String locationCode);
}
