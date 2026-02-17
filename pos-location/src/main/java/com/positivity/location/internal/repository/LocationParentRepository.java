package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.LocationParent;
import com.positivity.location.internal.entity.ParentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LocationParentRepository extends JpaRepository<LocationParent, UUID> {
    boolean existsByChild_IdAndParentType(UUID childId, ParentType parentType);

    Optional<LocationParent> findByChild_IdAndParentType(UUID childId, ParentType parentType);

    List<LocationParent> findByParent_Id(UUID parentId);

    List<LocationParent> findByParent_IdAndParentType(UUID parentId, ParentType parentType);
}
