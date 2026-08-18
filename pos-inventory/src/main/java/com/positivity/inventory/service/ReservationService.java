package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.reservation.CreateReservationRequest;
import com.positivity.inventory.internal.dto.reservation.PromoteAllocationRequest;
import com.positivity.inventory.internal.dto.reservation.ReservationResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface ReservationService {

    /**
     * Creates a reservation, or updates the existing one for the request's demand line. Exactly
     * one of {@code request.workorderLineId}/{@code request.salesOrderLineId} must be set (CAP
     * #1315); the demand-line kind is fixed at creation and cannot change on update.
     */
    @NonNull
    ReservationResponse createOrUpdateReservation(@NonNull CreateReservationRequest request);

    @NonNull
    ReservationResponse promoteToHard(@NonNull UUID allocationId, @NonNull PromoteAllocationRequest request);

    /** Cancels the reservation for a workorder line. */
    void cancelReservation(@NonNull UUID workorderLineId);

    /** Cancels the reservation for a sales-order line (CAP #1315). */
    void cancelReservationForSalesOrderLine(@NonNull UUID salesOrderLineId);
}
