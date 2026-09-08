package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.ExtJobTimeReplica;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtJobTimeReplicaRepository extends JpaRepository<ExtJobTimeReplica, UUID> {

    /**
     * Job-time rows in a UTC window with optional location/technician filters pushed into the
     * database (same optional-filter idiom as TimeEntryRepository). Rows without a locationId are
     * excluded — the report keys on technician+location+date. Timezone bucketing of
     * {@code endAtUtc} into local dates happens in the caller.
     */
    @NonNull
    @Query("""
                        SELECT j
                        FROM ExtJobTimeReplica j
                        WHERE j.endAtUtc >= :fromInclusive
                          AND j.endAtUtc < :toExclusive
                          AND j.locationId IS NOT NULL
                          AND (:locationId IS NULL OR j.locationId = :locationId)
                          AND (:includeAllTechnicians = true OR j.technicianId IN :technicianIds)
                        """)
    List<ExtJobTimeReplica> findForReportWindow(
            @Param("fromInclusive") @NonNull Instant fromInclusive,
            @Param("toExclusive") @NonNull Instant toExclusive,
            @Param("locationId") UUID locationId,
            @Param("technicianIds") @NonNull List<UUID> technicianIds,
            @Param("includeAllTechnicians") boolean includeAllTechnicians);

    /**
     * {@link #findForReportWindow} restricted to a set of locations — the job-time side of the
     * discrepancy report for a location-scoped caller who named no location (ADR-0061 §3,
     * #1872). The set is the caller's reach and is never empty here; the service short-circuits
     * an empty reach before querying.
     */
    @NonNull
    @Query("""
                        SELECT j
                        FROM ExtJobTimeReplica j
                        WHERE j.endAtUtc >= :fromInclusive
                          AND j.endAtUtc < :toExclusive
                          AND j.locationId IN :locationIds
                          AND (:includeAllTechnicians = true OR j.technicianId IN :technicianIds)
                        """)
    List<ExtJobTimeReplica> findForReportWindowWithinLocations(
            @Param("fromInclusive") @NonNull Instant fromInclusive,
            @Param("toExclusive") @NonNull Instant toExclusive,
            @Param("locationIds") @NonNull Collection<UUID> locationIds,
            @Param("technicianIds") @NonNull List<UUID> technicianIds,
            @Param("includeAllTechnicians") boolean includeAllTechnicians);
}
