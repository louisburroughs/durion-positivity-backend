package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.SkillCodeXref;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillCodeXrefRepository extends JpaRepository<SkillCodeXref, UUID> {
    /** Callers normalise to upper-case and trim first; stored values already are (spec D8). */
    Optional<SkillCodeXref> findBySourceCodeAndSourceSkillCode(@NonNull String sourceCode, @NonNull String sourceSkillCode);

    @NonNull
    List<SkillCodeXref> findAllByOrderBySourceCodeAscSourceSkillCodeAsc();
}
