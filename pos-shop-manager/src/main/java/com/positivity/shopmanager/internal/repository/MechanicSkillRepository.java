package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.MechanicSkill;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MechanicSkillRepository extends JpaRepository<MechanicSkill, UUID> {
    void deleteAllByMechanicId(UUID mechanicId);

    @NonNull
    List<MechanicSkill> findAllByMechanicIdIn(@NonNull List<UUID> mechanicIds);
}
