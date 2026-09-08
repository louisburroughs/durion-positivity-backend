package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.LocationParent;
import com.positivity.location.internal.entity.ParentType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationParentRepository extends JpaRepository<LocationParent, UUID> {
    boolean existsByChild_IdAndParentType(UUID childId, ParentType parentType);

    Optional<LocationParent> findByChild_IdAndParentType(UUID childId, ParentType parentType);

    boolean existsByChild_IdAndParent_Id(UUID childId, UUID parentId);

    /**
     * Per-dimension edge existence check used by the post-persist cycle race guard: an
     * inverse edge on a different {@link ParentType} is a legal DAG, not a cycle (ADR-0061).
     *
     * Issue: #1878
     */
    boolean existsByChild_IdAndParent_IdAndParentType(UUID childId, UUID parentId, ParentType parentType);

    List<LocationParent> findByParent_Id(UUID parentId);

    List<LocationParent> findByChild_Id(UUID childId);

    List<LocationParent> findByParent_IdAndParentType(UUID parentId, ParentType parentType);

    /**
     * Batched downward traversal helper: fetches all parent edges of the given
     * type for one whole frontier level in a single query.
     *
     * Issue: CAP-214 #655
     *
     * @param parentIds  ids of the locations forming the current frontier level
     * @param parentType relationship type to traverse
     * @return all matching parent edges
     */
    List<LocationParent> findByParent_IdInAndParentType(Collection<UUID> parentIds, ParentType parentType);
}
