package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ItemCostEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemCostRepository extends JpaRepository<ItemCostEntity, UUID> {

    Optional<ItemCostEntity> findByItemId(UUID itemId);
}
