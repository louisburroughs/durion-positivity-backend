package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.InventoryVariance;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryVarianceRepository extends JpaRepository<InventoryVariance, UUID> {}
