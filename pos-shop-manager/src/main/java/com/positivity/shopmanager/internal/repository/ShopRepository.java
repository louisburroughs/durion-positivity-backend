package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.Shop;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopRepository extends JpaRepository<Shop, UUID> {}
