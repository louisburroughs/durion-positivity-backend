package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.LocationProfile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * Batch, case-insensitive form of {@link #findByLocationCode} for display resolution (issues
     * #1778, #1797): one {@code IN} query for every location referenced by a response, rather
     * than one per reference. Event producers do not all spell a location code the way the
     * profile stores it, so the comparison is on the upper-cased code.
     *
     * @param upperCaseLocationCodes the location codes to look up, already upper-cased by the
     *                               caller
     * @return the profiles that exist; codes with no accounting-side master data are simply absent
     */
    @NonNull
    @Query("select p from LocationProfile p where upper(p.locationCode) in :codes")
    List<LocationProfile> findByLocationCodeInIgnoreCase(
            @NonNull @Param("codes") Collection<String> upperCaseLocationCodes);
}
