package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.SupplierItemCostEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierItemCostRepository
        extends JpaRepository<SupplierItemCostEntity, UUID>, JpaSpecificationExecutor<SupplierItemCostEntity> {

    boolean existsBySupplierIdAndItemId(UUID supplierId, UUID itemId);

    Optional<SupplierItemCostEntity> findBySupplierIdAndItemId(UUID supplierId, UUID itemId);

    void deleteBySupplierIdAndItemId(UUID supplierId, UUID itemId);
}
