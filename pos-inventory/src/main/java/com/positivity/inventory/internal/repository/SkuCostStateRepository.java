package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.SkuCostState;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for per-SKU {@link SkuCostState} running cost rows (odoo-parity J1, issue #1048). */
public interface SkuCostStateRepository extends JpaRepository<SkuCostState, UUID> {

    Optional<SkuCostState> findByStockItemId(String stockItemId);

    List<SkuCostState> findByStockItemIdIn(Collection<String> stockItemIds);
}
