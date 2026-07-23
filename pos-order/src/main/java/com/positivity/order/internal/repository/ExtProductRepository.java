package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtProduct;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtProductRepository extends JpaRepository<ExtProduct, UUID> {

    Optional<ExtProduct> findFirstBySkuIgnoreCaseAndActiveTrue(String sku);
}
