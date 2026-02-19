package com.positivity.shopmanager.internal.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.shopmanager.internal.entity.ShopQualification;

public interface ShopQualificationRepository extends JpaRepository<ShopQualification, UUID> {
}