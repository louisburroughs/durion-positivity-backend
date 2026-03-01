package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.MechanicSkill;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MechanicSkillRepository extends JpaRepository<MechanicSkill, UUID> {
    void deleteAllByMechanicId(UUID mechanicId);
}
