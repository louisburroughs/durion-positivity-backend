package com.positivity.vehicle.service;

import com.positivity.vehicle.internal.dto.VehicleUpdatedEvent;
import com.positivity.vehicle.internal.entity.EventProcessingLog;
import com.positivity.vehicle.internal.entity.EventProcessingLog.ConflictPolicy;
import com.positivity.vehicle.internal.entity.EventProcessingLog.ProcessingStatus;
import com.positivity.vehicle.internal.entity.VehicleRecord;
import com.positivity.vehicle.internal.entity.VehicleRecord.OdometerReading;
import com.positivity.vehicle.internal.enums.OdometerUnit;
import com.positivity.vehicle.internal.repository.EventProcessingLogRepository;
import com.positivity.vehicle.internal.repository.VehicleRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.*;

/**
 * Event ingestion service for vehicle updates (CAP:091 Story #101).
 * Processes VehicleUpdated events with idempotency and conflict resolution.
 * <p>
 * Conflict policies:
 * - MILEAGE_DECREASE: ACCEPT_AND_FLAG - accept decrease, flag for review
 * - VIN_CHANGE: REJECT - reject VIN changes (controlled via separate API)
 * - ODOMETER_INVALID: REJECT - reject odometer <0 or >999999
 * - DUPLICATE_EVENT: SKIP - skip if eventId already processed
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class VehicleEventIngestionService {

    private final VehicleRecordRepository vehicleRepository;
    private final EventProcessingLogRepository eventProcessingLogRepository;
    private final VehicleService vehicleService;

    /**
     * Process a VehicleUpdated event with conflict resolution and idempotency.
     *
     * @param event VehicleUpdated event from message broker
     */
    @Transactional
    public void processVehicleUpdateEvent(@NonNull VehicleUpdatedEvent event) {
        String eventId = event.getEventId();
        String workorderId = event.getWorkorderId();
        String vehicleId = event.getVehicleId();

        log.info("Processing VehicleUpdated event: id={}, workorder={}, vehicle={}",
                eventId, workorderId, vehicleId);

        // 1. Check for duplicate (idempotency)
        Optional<EventProcessingLog> existingLog = eventProcessingLogRepository.findByEventId(eventId);
        if (existingLog.isPresent()) {
            log.warn("Duplicate event detected: id={} (already processed with status {})",
                    eventId, existingLog.get().getStatus());
            updateEventProcessingLog(existingLog.get(), ProcessingStatus.DUPLICATE_EVENT,
                    ConflictPolicy.DUPLICATE_EVENT, "Already processed");
            return;
        }

        // 2. Load vehicle
        Optional<VehicleRecord> vehicleOpt = vehicleRepository.findById(UUID.fromString(vehicleId));
        if (vehicleOpt.isEmpty()) {
            log.error("Vehicle not found: id={}", vehicleId);
            var eventLog = createEventProcessingLog(eventId, workorderId, vehicleId,
                    ProcessingStatus.ERROR_VEHICLE_NOT_FOUND);
            eventProcessingLogRepository.save(eventLog);
            return;
        }

        VehicleRecord vehicle = vehicleOpt.get();

        // 3. Validate VIN change (not allowed; controlled separately)
        if (!vehicle.getVinNormalized().equals(event.getVinNormalized())) {
            log.warn("VIN change rejected: vehicle={}, old={}, new={}",
                    vehicleId, vehicle.getVinNormalized(), event.getVinNormalized());
            var eventLog = createEventProcessingLog(eventId, workorderId, vehicleId,
                    ProcessingStatus.CONFLICT_VIN_CHANGE);
            eventLog.setConflictPolicy(ConflictPolicy.VIN_CHANGE);
            eventLog.setDetails(Map.of("oldVin", vehicle.getVinNormalized(), "newVin", event.getVinNormalized()));
            eventProcessingLogRepository.save(eventLog);
            return;
        }

        // 4. Validate odometer
        if (event.getCurrentMileage() != null) {
            if (event.getCurrentMileage() < 0 || event.getCurrentMileage() > 999999) {
                log.error("Invalid mileage: vehicle={}, mileage={}", vehicleId, event.getCurrentMileage());
                var eventLog = createEventProcessingLog(eventId, workorderId, vehicleId,
                        ProcessingStatus.ERROR_INVALID_ODOMETER);
                eventLog.setDetails(Map.of("mileage", event.getCurrentMileage()));
                eventProcessingLogRepository.save(eventLog);
                return;
            }

            // 5. Check mileage decrease (warning but accept)
            Long currentMileage = vehicle.getOdometer() != null ? vehicle.getOdometer().getValue() : null;
            if (currentMileage != null && event.getCurrentMileage() < currentMileage) {
                log.warn("Mileage decrease detected: vehicle={}, old={}, new={}",
                        vehicleId, currentMileage, event.getCurrentMileage());
                // Flag for review but accept (per business rules)
                applyMileageUpdate(vehicle, event);
                var eventLog = createEventProcessingLog(eventId, workorderId, vehicleId,
                        ProcessingStatus.PENDING_REVIEW);
                eventLog.setConflictPolicy(ConflictPolicy.MILEAGE_DECREASE);
                eventLog.setDetails(Map.of("oldMileage", currentMileage, "newMileage", event.getCurrentMileage()));
                eventProcessingLogRepository.save(eventLog);
                return;
            }
        }

        // 6. Apply update
        try {
            applyUpdate(vehicle, event);
            var eventLog = createEventProcessingLog(eventId, workorderId, vehicleId, ProcessingStatus.SUCCESS);
            eventLog.setDetails(Map.of("updateType", "mileage_and_metadata", "appliedAt", Instant.now().toString()));
            eventProcessingLogRepository.save(eventLog);
            log.info("Successfully processed event: id={}", eventId);
        } catch (Exception e) {
            log.error("Error processing event: id={}", eventId, e);
            var errorLog = createEventProcessingLog(eventId, workorderId, vehicleId, ProcessingStatus.ERROR_PROCESSING);
            errorLog.setDetails(Map.of("error", e.getMessage()));
            eventProcessingLogRepository.save(errorLog);
        }
    }

    /**
     * Apply mileage update to vehicle.
     */
    private void applyMileageUpdate(@NonNull VehicleRecord vehicle, @NonNull VehicleUpdatedEvent event) {
        if (event.getCurrentMileage() != null) {
            OdometerReading reading = OdometerReading.builder()
                    .value(event.getCurrentMileage())
                    .unit(OdometerUnit.MILES)
                    .asOfDateTime(Instant.now())
                    .build();
            vehicle.setOdometer(reading);
            vehicleRepository.save(vehicle);
        }
    }

    /**
     * Apply full update (mileage + metadata).
     */
    private void applyUpdate(@NonNull VehicleRecord vehicle, @NonNull VehicleUpdatedEvent event) {
        // Update mileage
        applyMileageUpdate(vehicle, event);

        // Update metadata if present
        if (event.getDescription() != null) {
            vehicle.setDescription(event.getDescription());
        }
        if (event.getYear() != null) {
            vehicle.setYear(event.getYear());
        }
        if (event.getMake() != null) {
            vehicle.setMake(event.getMake());
        }
        if (event.getModel() != null) {
            vehicle.setModel(event.getModel());
        }

        vehicleRepository.save(vehicle);
    }

    /**
     * Create event processing log for new event.
     */
    private EventProcessingLog createEventProcessingLog(@NonNull String eventId, @NonNull String workorderId,
            @NonNull String vehicleId, @NonNull ProcessingStatus status) {
        return EventProcessingLog.builder()
                .eventId(eventId)
                .workorderId(workorderId)
                .vehicleId(vehicleId)
                .status(status)
                .processedAt(Instant.now())
                .build();
    }

    /**
     * Update existing event processing log.
     */
    private void updateEventProcessingLog(@NonNull EventProcessingLog log, @NonNull ProcessingStatus status,
            @Nullable ConflictPolicy policy, @Nullable String note) {
        log.setStatus(status);
        if (policy != null) {
            log.setConflictPolicy(policy);
        }
        if (note != null) {
            Map<String, Object> details = log.getDetails() != null ? new HashMap<>(log.getDetails()) : new HashMap<>();
            details.put("note", note);
            log.setDetails(details);
        }
        log.setProcessedAt(Instant.now());
        eventProcessingLogRepository.save(log);
    }

    /**
     * Get processing history for a vehicle.
     */
    @Transactional(readOnly = true)
    public List<EventProcessingLog> getProcessingHistory(@NonNull String vehicleId) {
        return eventProcessingLogRepository.findByVehicleId(vehicleId);
    }

    /**
     * Get processing log by event ID.
     */
    @Transactional(readOnly = true)
    public Optional<EventProcessingLog> getProcessingLog(@NonNull String eventId) {
        return eventProcessingLogRepository.findByEventId(eventId);
    }
}
