package com.positivity.shopManager.internal.repository;

import com.positivity.shopManager.internal.entity.ShopService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShopServiceRepository extends JpaRepository<ShopService, Long> {
    Optional<ShopService> findByIdAndShopId(Long id, Long shopId);
}