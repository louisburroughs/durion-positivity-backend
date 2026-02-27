package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.AsnLineEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AsnLineRepository extends JpaRepository<AsnLineEntity, UUID> {
}
