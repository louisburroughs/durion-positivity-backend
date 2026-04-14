package com.positivity.vehicle.service;

import com.positivity.vehicle.internal.dto.VehicleUpdatedEvent;
import com.positivity.vehicle.internal.entity.EventProcessingLog;
import java.util.List;
import java.util.Optional;

public interface VehicleEventIngestionService {

    /**
     * Process a VehicleUpdated event with conflict resolution and idempotency.
     *
     * @param event VehicleUpdated event from message broker
     */
    void processVehicleUpdateEvent(VehicleUpdatedEvent event);

    /**
     * Get processing history for a vehicle.
     */
    List<EventProcessingLog> getProcessingHistory(String vehicleId);

    /**
     * Get processing log by event ID.
     */
    Optional<EventProcessingLog> getProcessingLog(String eventId);
}
