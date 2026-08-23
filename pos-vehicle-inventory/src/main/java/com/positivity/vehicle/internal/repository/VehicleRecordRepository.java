package com.positivity.vehicle.internal.repository;

import com.positivity.vehicle.internal.entity.VehicleRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleRecordRepository extends JpaRepository<VehicleRecord, UUID> {

    Optional<VehicleRecord> findByVinNormalized(@NonNull String vinNormalized);

    Optional<VehicleRecord> findByVehicleId(@NonNull UUID vehicleId);

    @Query("SELECT v FROM VehicleRecord v WHERE " + "LOWER(v.vinNormalized) LIKE LOWER(CONCAT(:query, '%')) OR "
            + "LOWER(v.unitNumber) LIKE LOWER(CONCAT(:query, '%')) OR "
            + "LOWER(v.licensePlate) LIKE LOWER(CONCAT(:query, '%'))")
    List<VehicleRecord> searchByQuery(@Param("query") String query);

    List<VehicleRecord> findByAccountId(@NonNull UUID accountId);

    boolean existsByVinNormalizedAndIsActiveTrue(@NonNull String vinNormalized);

    /**
     * One page of vehicles for a fact replay, ordered by id so a cursor can resume.
     *
     * <p>Reads the vehicles themselves rather than the outbox: a replay exists precisely for the
     * rows whose facts were never written, so replaying the outbox would re-emit only what already
     * reached consumers. Mirrors {@code ProductRepository.findForReplay} in pos-catalog.
     */
    @Query("""
      SELECT v FROM VehicleRecord v
      WHERE (:afterId IS NULL OR v.vehicleId > :afterId)
        AND (:updatedSince IS NULL OR v.updatedAt >= :updatedSince)
      ORDER BY v.vehicleId ASC
      """)
    List<VehicleRecord> findForReplay(
            @Param("afterId") UUID afterId, @Param("updatedSince") Instant updatedSince, Pageable pageable);
}
