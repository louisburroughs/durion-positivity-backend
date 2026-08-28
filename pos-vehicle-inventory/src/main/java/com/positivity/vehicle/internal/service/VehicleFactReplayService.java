package com.positivity.vehicle.internal.service;

import com.positivity.vehicle.internal.dto.VehicleFactReplayResultDto;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Re-emits vehicle facts so event-fed replicas can be seeded or repaired. */
public interface VehicleFactReplayService {

    @NonNull
    VehicleFactReplayResultDto replayPage(@Nullable UUID afterVehicleId, @Nullable Instant updatedSince, int limit);
}
