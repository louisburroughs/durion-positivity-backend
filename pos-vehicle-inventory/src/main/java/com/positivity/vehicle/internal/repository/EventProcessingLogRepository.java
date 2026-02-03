package com.positivity.vehicle.internal.repository;

import com.positivity.vehicle.internal.entity.EventProcessingLog;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventProcessingLogRepository extends JpaRepository<EventProcessingLog, UUID> {

    Optional<EventProcessingLog> findByEventId(@NonNull String eventId);

    boolean existsByEventId(@NonNull String eventId);

    List<EventProcessingLog> findByVehicleId(@NonNull String vehicleId);
}
