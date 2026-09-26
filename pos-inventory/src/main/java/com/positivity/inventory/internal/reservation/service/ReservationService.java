package com.positivity.inventory.internal.reservation.service;

import com.positivity.inventory.internal.dto.reservation.CreateReservationRequest;
import com.positivity.inventory.internal.dto.reservation.PromoteAllocationRequest;
import com.positivity.inventory.internal.dto.reservation.ReservationResponse;
import com.positivity.inventory.internal.dto.reservation.WorkorderReservationResponse;
import java.util.List;
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

    /**
     * Reservations for a workorder's part lines, with their allocations (issue #2233): the
     * allocation-id lookup the shortage page needs. The workorder's lines
     * ({@code ExtWorkorderPartReplicaRepository.findByWorkorderId}) are resolved to reservations in
     * one batched query ({@code ReservationRepository.findByWorkorderLineIdIn}), never a per-line
     * loop. Each reservation's allocations are narrowed to the caller's location-scope reach
     * (ADR-0061 §3) — an allocation whose location the caller cannot reach is dropped, but a
     * reservation left with no in-reach allocation is still returned with its quantities.
     *
     * @return an empty list when the workorder has no lines, has lines but no reservations, or is
     *     unknown (no replica row is not an error here)
     */
    @NonNull
    List<WorkorderReservationResponse> listReservationsForWorkorder(@NonNull UUID workorderId);
}
