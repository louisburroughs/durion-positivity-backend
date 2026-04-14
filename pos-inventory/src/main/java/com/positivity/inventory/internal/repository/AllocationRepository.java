package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.AllocationEntity;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.AllocationState;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AllocationRepository extends JpaRepository<AllocationEntity, UUID> {

    List<AllocationEntity> findByReservation(ReservationEntity reservation);

    List<AllocationEntity> findByReservationAndAllocationState(ReservationEntity reservation, AllocationState state);
}
