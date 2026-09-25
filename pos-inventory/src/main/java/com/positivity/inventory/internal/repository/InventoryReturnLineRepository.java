package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.InventoryReturnLineEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link InventoryReturnLineEntity} rows, keyed off the work order line (#2206). */
public interface InventoryReturnLineRepository extends JpaRepository<InventoryReturnLineEntity, UUID> {

    List<InventoryReturnLineEntity> findByWorkorderLineIdIn(Collection<UUID> workorderLineIds);
}
