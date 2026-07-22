package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ProductUomEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductUomRepository extends JpaRepository<ProductUomEntity, UUID> {

    List<ProductUomEntity> findByProductIdOrderByUomCodeAsc(UUID productId);

    Optional<ProductUomEntity> findByProductIdAndUomCode(UUID productId, String uomCode);

    boolean existsByProductIdAndUomCode(UUID productId, String uomCode);
}
