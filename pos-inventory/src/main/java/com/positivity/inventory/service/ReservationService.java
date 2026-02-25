package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.reservation.CreateReservationRequest;
import com.positivity.inventory.internal.dto.reservation.PromoteAllocationRequest;
import com.positivity.inventory.internal.dto.reservation.ReservationResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface ReservationService {

    @NonNull
    ReservationResponse createOrUpdateReservation(@NonNull CreateReservationRequest request);

    @NonNull
    ReservationResponse promoteToHard(@NonNull UUID allocationId, @NonNull PromoteAllocationRequest request);

    void cancelReservation(@NonNull UUID workorderLineId);
}
