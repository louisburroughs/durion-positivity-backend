package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.Technician;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TechnicianRepository extends JpaRepository<Technician, UUID> {
    Optional<Technician> findByIdAndShopId(UUID id, UUID shopId);

    Optional<Technician> findFirstByShopIdAndPersonId(UUID shopId, UUID personId);

    @Query("""
            SELECT technician
            FROM Technician technician, Mechanic mechanic
            WHERE technician.shop.id = :locationId
              AND mechanic.personId = CAST(technician.personId AS string)
              AND mechanic.status = :status
              AND (:skillCode IS NULL OR EXISTS (
                SELECT skill.id
                FROM MechanicSkill skill
                WHERE skill.mechanic = mechanic
                  AND skill.skillCode = :skillCode))
            ORDER BY mechanic.lastName, mechanic.firstName, mechanic.personId
            """)
    @NonNull
    Page<Technician> findRosterByLocation(
            @Param("locationId") @NonNull UUID locationId,
            @Param("status") @NonNull MechanicStatus status,
            @Param("skillCode") @Nullable String skillCode,
            @NonNull Pageable pageable);
}
