package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ReservationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<ReservationEntity, UUID> {

    List<ReservationEntity> findBySku(String sku);

    Optional<ReservationEntity> findByWorkorderLineId(UUID workorderLineId);
}
