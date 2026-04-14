package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ShopQualification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopQualificationRepository extends JpaRepository<ShopQualification, UUID> {}
