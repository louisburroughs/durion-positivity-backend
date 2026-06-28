package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.MobileUnit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MobileUnitRepository extends JpaRepository<MobileUnit, UUID> {}
