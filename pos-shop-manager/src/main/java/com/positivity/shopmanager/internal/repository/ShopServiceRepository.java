package com.positivity.shopmanager.internal.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.shopmanager.internal.entity.ShopService;

public interface ShopServiceRepository extends JpaRepository<ShopService, UUID> {
    Optional<ShopService> findByIdAndShopId(UUID id, UUID shopId);
}