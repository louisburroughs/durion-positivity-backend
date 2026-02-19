package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.LocationParent;
import com.positivity.location.internal.entity.ParentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LocationParentRepository extends JpaRepository<LocationParent, UUID> {
    boolean existsByChild_IdAndParentType(UUID childId, ParentType parentType);

    Optional<LocationParent> findByChild_IdAndParentType(UUID childId, ParentType parentType);

    boolean existsByChild_IdAndParent_Id(UUID childId, UUID parentId);

    @Query(value = """
            WITH RECURSIVE descendants(child_id) AS (
                SELECT lp.child_id
                FROM location_parent lp
                WHERE lp.parent_id = :ancestorId
              UNION
                SELECT lp.child_id
                FROM location_parent lp
                JOIN descendants d ON lp.parent_id = d.child_id
            )
            SELECT EXISTS (
                SELECT 1
                FROM descendants
                WHERE child_id = :targetDescendantId
            )
            """, nativeQuery = true)
    boolean isDescendant(@Param("ancestorId") UUID ancestorId, @Param("targetDescendantId") UUID targetDescendantId);

    List<LocationParent> findByParent_Id(UUID parentId);

    List<LocationParent> findByParent_IdAndParentType(UUID parentId, ParentType parentType);
}
