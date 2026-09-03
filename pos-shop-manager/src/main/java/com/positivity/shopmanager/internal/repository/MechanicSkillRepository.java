package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.MechanicSkill;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link MechanicSkill} maps its parent as the {@code mechanic} association; its {@code getMechanicId()}
 * is a convenience getter, not a mapped attribute. A query derived from a {@code ...ByMechanicId} method
 * name is therefore rendered as {@code skill.mechanicId}, which Hibernate rejects at first use (#1679:
 * the mechanic roster page answered 400 for every ACTIVE lookup). Both lookups below walk the
 * association explicitly instead of relying on name derivation.
 */
public interface MechanicSkillRepository extends JpaRepository<MechanicSkill, UUID> {

    @Modifying
    @Query("DELETE FROM MechanicSkill skill WHERE skill.mechanic.mechanicId = :mechanicId")
    void deleteAllByMechanicId(@Param("mechanicId") @NonNull UUID mechanicId);

    @Query("""
            SELECT skill
            FROM MechanicSkill skill
            WHERE skill.mechanic.mechanicId IN :mechanicIds
            """)
    @NonNull
    List<MechanicSkill> findAllByMechanicIdIn(@Param("mechanicIds") @NonNull List<UUID> mechanicIds);
}
