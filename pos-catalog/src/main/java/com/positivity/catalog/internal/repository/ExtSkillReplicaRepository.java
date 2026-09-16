package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ExtSkillReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Skill registry replica rows (CAP-329); written only by the people-events consumer. */
public interface ExtSkillReplicaRepository extends JpaRepository<ExtSkillReplica, UUID> {

    @NonNull
    List<ExtSkillReplica> findAllByOrderByCodeAsc();

    @NonNull
    List<ExtSkillReplica> findAllBySkillIdIn(@NonNull Collection<UUID> skillIds);
}
