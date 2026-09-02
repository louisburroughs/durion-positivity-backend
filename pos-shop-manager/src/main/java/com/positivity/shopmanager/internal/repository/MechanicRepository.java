package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
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

public interface MechanicRepository extends JpaRepository<Mechanic, UUID> {
    Optional<Mechanic> findByPersonId(String personId);

    List<Mechanic> findAllByStatus(MechanicStatus status);

    @NonNull
    List<Mechanic> findAllByPersonIdIn(@NonNull List<String> personIds);

    @Query("""
                        SELECT mechanic
                        FROM Mechanic mechanic
                        WHERE mechanic.status = :status
                            AND (:skillCode IS NULL OR EXISTS (
                                        SELECT skill.id
                                        FROM MechanicSkill skill
                                        WHERE skill.mechanic = mechanic
                                            AND skill.skillCode = :skillCode))
                        """)
    @NonNull
    Page<Mechanic> findRoster(
            @Param("status") @NonNull MechanicStatus status,
            @Param("skillCode") @Nullable String skillCode,
            @NonNull Pageable pageable);
}
