package com.positivity.vehicle.internal.repository;

import com.positivity.vehicle.internal.entity.VehicleRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface VehicleRecordRepository extends JpaRepository<VehicleRecord, UUID> {

    Optional<VehicleRecord> findByVinNormalized(@NonNull String vinNormalized);

    Optional<VehicleRecord> findByVehicleId(@NonNull UUID vehicleId);

    @Query("SELECT v FROM VehicleRecord v WHERE " + "LOWER(v.vinNormalized) LIKE LOWER(CONCAT(:query, '%')) OR "
            + "LOWER(v.unitNumber) LIKE LOWER(CONCAT(:query, '%')) OR "
            + "LOWER(v.licensePlate) LIKE LOWER(CONCAT(:query, '%'))")
    List<VehicleRecord> searchByQuery(@Param("query") String query);

    List<VehicleRecord> findByAccountId(@NonNull UUID accountId);

    boolean existsByVinNormalizedAndIsActiveTrue(@NonNull String vinNormalized);
}
