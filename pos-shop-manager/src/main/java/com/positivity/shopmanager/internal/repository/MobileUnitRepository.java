package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.MobileUnit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MobileUnitRepository extends JpaRepository<MobileUnit, UUID> {
}
