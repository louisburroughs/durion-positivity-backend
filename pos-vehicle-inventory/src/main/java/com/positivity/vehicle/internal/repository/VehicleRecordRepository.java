package com.positivity.vehicle.internal.repository;

import com.positivity.vehicle.internal.entity.VehicleRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleRecordRepository
        extends JpaRepository<VehicleRecord, UUID>, JpaSpecificationExecutor<VehicleRecord> {

    Optional<VehicleRecord> findByVinNormalized(@NonNull String vinNormalized);

    Optional<VehicleRecord> findByVehicleId(@NonNull UUID vehicleId);

    @Query("SELECT v FROM VehicleRecord v WHERE " + "LOWER(v.vinNormalized) LIKE LOWER(CONCAT(:query, '%')) OR "
            + "LOWER(v.unitNumber) LIKE LOWER(CONCAT(:query, '%')) OR "
            + "LOWER(v.licensePlate) LIKE LOWER(CONCAT(:query, '%'))")
    List<VehicleRecord> searchByQuery(@Param("query") String query);

    List<VehicleRecord> findByAccountId(@NonNull UUID accountId);

    boolean existsByVinNormalizedAndIsActiveTrue(@NonNull String vinNormalized);

    /**
     * The replay order: by vehicle id ascending, so a cursor resumes exactly where the previous page
     * stopped. Imposed by the search rather than taken from the caller, because it is what makes
     * {@code afterId} a cursor at all.
     */
    Sort BY_VEHICLE_ID = Sort.by(Sort.Order.asc("vehicleId"));

    /**
     * One page of vehicles for a fact replay, ordered by id so a cursor can resume.
     *
     * <p>Reads the vehicles themselves rather than the outbox: a replay exists precisely for the
     * rows whose facts were never written, so replaying the outbox would re-emit only what already
     * reached consumers. Mirrors {@code ProductRepository.findForReplay} in pos-catalog.
     *
     * <p>The filter is a {@link VehicleFactReplaySearch} specification rather than a JPQL string of
     * {@code (:param IS NULL OR …)} clauses: see that class for why the string form returned 500
     * from PostgreSQL for every call while passing on H2 (issue #1891).
     *
     * @param afterId resume strictly after this vehicle id, or null to start at the beginning
     * @param updatedSince only vehicles changed at or after this instant, or null for every vehicle
     * @param pageable supplies the page size only — the replay is positioned by {@code afterId}, not
     *     by an offset, and the order is always {@link #BY_VEHICLE_ID}
     * @return one page of vehicles, oldest id first
     */
    @NonNull
    default List<VehicleRecord> findForReplay(
            @Nullable UUID afterId, @Nullable Instant updatedSince, @NonNull Pageable pageable) {
        return findBy(VehicleFactReplaySearch.matching(afterId, updatedSince), query -> {
            var sorted = query.sortBy(BY_VEHICLE_ID);
            return (pageable.isUnpaged() ? sorted : sorted.limit(pageable.getPageSize())).all();
        });
    }
}
