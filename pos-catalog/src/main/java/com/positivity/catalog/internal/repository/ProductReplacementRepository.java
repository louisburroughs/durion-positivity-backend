package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ProductReplacementEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductReplacementRepository extends JpaRepository<ProductReplacementEntity, UUID> {
    List<ProductReplacementEntity> findByOriginalProduct_IdAndDeletedAtIsNullOrderByPriorityOrderAsc(
            UUID originalProductId);
}
