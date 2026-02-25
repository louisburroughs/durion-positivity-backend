package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.PickListEntity;
import com.positivity.inventory.internal.entity.PickTaskEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickTaskRepository extends JpaRepository<PickTaskEntity, UUID> {

    List<PickTaskEntity> findByPickListOrderBySortOrderAsc(PickListEntity pickList);

    List<PickTaskEntity> findByPickList_PickListId(UUID pickListId);
}