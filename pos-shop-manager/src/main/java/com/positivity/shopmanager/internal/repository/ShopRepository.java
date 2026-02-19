package com.positivity.shopmanager.internal.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.shopmanager.internal.entity.Shop;

@Repository
public interface ShopRepository extends JpaRepository<Shop, UUID> {
}

