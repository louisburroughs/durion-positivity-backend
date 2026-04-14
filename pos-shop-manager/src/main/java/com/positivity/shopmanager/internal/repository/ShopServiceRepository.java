package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ShopServiceEntry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopServiceRepository extends JpaRepository<ShopServiceEntry, UUID> {
    Optional<ShopServiceEntry> findByIdAndShopId(UUID id, UUID shopId);
}
