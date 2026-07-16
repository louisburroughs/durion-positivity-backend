package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.enums.ClaimStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WarrantyClaimRepository extends JpaRepository<WarrantyClaim, UUID> {

    /** Lookup by business identifier {@code WC-yyyy-nnnnnn} (PRD §4). */
    @NonNull
    Optional<WarrantyClaim> findByClaimCode(@NonNull String claimCode);

    @NonNull
    List<WarrantyClaim> findByCustomerId(@NonNull UUID customerId);

    @NonNull
    List<WarrantyClaim> findByVehicleId(@NonNull UUID vehicleId);

    @NonNull
    List<WarrantyClaim> findByStatus(@NonNull ClaimStatus status);

    @NonNull
    List<WarrantyClaim> findByLocationId(@NonNull UUID locationId);

    /**
     * Combined claim search (PRD §8 {@code GET /v1/warranty/claims?...}); every filter is
     * optional — pass null to skip a leg.
     */
    @NonNull
    @Query("select c from WarrantyClaim c"
            + " where (:customerId is null or c.customerId = :customerId)"
            + " and (:vehicleId is null or c.vehicleId = :vehicleId)"
            + " and (:status is null or c.status = :status)"
            + " and (:locationId is null or c.locationId = :locationId)")
    Page<WarrantyClaim> search(
            @Param("customerId") @Nullable UUID customerId,
            @Param("vehicleId") @Nullable UUID vehicleId,
            @Param("status") @Nullable ClaimStatus status,
            @Param("locationId") @Nullable UUID locationId,
            @NonNull Pageable pageable);
}
