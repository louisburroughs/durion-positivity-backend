package com.positivity.vehicle.internal.service;

import com.positivity.vehicle.internal.config.VehicleEventPublisher;
import com.positivity.vehicle.internal.dto.VehicleFactReplayResultDto;
import com.positivity.vehicle.internal.entity.VehicleRecord;
import com.positivity.vehicle.internal.repository.VehicleRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-publishes {@code vehicle.vehicle.updated} for one bounded page of vehicles.
 *
 * <p>Exists because enabling publication only covers what changes afterwards. Vehicles written while
 * {@code POS_VEHICLE_INVENTORY_KAFKA_ENABLED} was false have no outbox rows at all, so the existing
 * outbox replay - which re-queues rows already in that table - cannot reach them, and every
 * ext_vehicle replica downstream stays missing them until someone happens to edit each vehicle.
 * This reads the vehicles themselves instead. Mirrors pos-catalog's product-fact replay.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleFactReplayServiceImpl implements VehicleFactReplayService {

    /** Bound on one call, so a mistyped limit cannot turn a replay into a broker flood. */
    public static final int MAX_LIMIT = 1000;

    private final VehicleRecordRepository vehicleRecordRepository;
    private final VehicleEventPublisher vehicleEventPublisher;
    private final Clock clock;

    @Override
    @NonNull
    @Transactional
    public VehicleFactReplayResultDto replayPage(
            @Nullable UUID afterVehicleId, @Nullable Instant updatedSince, int limit) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Instant startedAt = Instant.now(clock);

        List<VehicleRecord> vehicles =
                vehicleRecordRepository.findForReplay(afterVehicleId, updatedSince, PageRequest.of(0, pageSize));

        for (VehicleRecord vehicle : vehicles) {
            vehicleEventPublisher.publishVehicleUpdated(vehicle);
        }

        // A short page means the end was reached: the query is ordered by id and bounded by the
        // cursor, so there is nothing after it for this filter.
        boolean complete = vehicles.size() < pageSize;
        UUID nextAfterId =
                complete || vehicles.isEmpty() ? null : vehicles.getLast().getVehicleId();

        log.info(
                "Replayed {} vehicle facts (after={}, updatedSince={}, complete={})",
                vehicles.size(),
                afterVehicleId,
                updatedSince,
                complete);

        return new VehicleFactReplayResultDto(vehicles.size(), nextAfterId, complete, updatedSince, startedAt);
    }
}
