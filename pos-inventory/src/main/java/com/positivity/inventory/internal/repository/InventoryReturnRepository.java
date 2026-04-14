package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.InventoryReturnEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryReturnRepository extends JpaRepository<InventoryReturnEntity, UUID> {}
