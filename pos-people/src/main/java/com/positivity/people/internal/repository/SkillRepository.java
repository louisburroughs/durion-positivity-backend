package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.Skill;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read access to the seeded skill registry; the application never writes it. */
public interface SkillRepository extends JpaRepository<Skill, UUID> {
    Optional<Skill> findByCode(@NonNull String code);

    @NonNull
    List<Skill> findAllByActiveTrueOrderByCodeAsc();
}
