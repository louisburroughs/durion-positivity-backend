package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ReservationEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<ReservationEntity, UUID> {

    Optional<ReservationEntity> findByWorkorderLineId(UUID workorderLineId);
}
