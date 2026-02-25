package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.reservation.CreateReservationRequest;
import com.positivity.inventory.internal.dto.reservation.PromoteAllocationRequest;
import com.positivity.inventory.internal.dto.reservation.ReservationResponse;
import com.positivity.inventory.service.ReservationService;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/reservations")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('inventory:availability:read','inventory:adjustment:create')")
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping("/")
    @EmitEvent(id = "INVENTORY_RESERVATION_CREATE_OR_UPDATE", apiVersion = "1")
    public ResponseEntity<ReservationResponse> createOrUpdateReservation(
            @RequestBody CreateReservationRequest request) {
        ReservationResponse response = reservationService.createOrUpdateReservation(request);
        return ResponseEntity.created(URI.create("/v1/inventory/reservations/" + response.getReservationId()))
                .body(response);
    }

    @PostMapping("/{allocationId}/promote")
    @EmitEvent(id = "INVENTORY_ALLOCATION_PROMOTE_HARD", apiVersion = "1")
    public ResponseEntity<ReservationResponse> promoteToHard(
            @PathVariable UUID allocationId,
            @RequestBody PromoteAllocationRequest request) {
        return ResponseEntity.ok(reservationService.promoteToHard(allocationId, request));
    }

    @DeleteMapping("/{workorderLineId}")
    @EmitEvent(id = "INVENTORY_RESERVATION_CANCEL", apiVersion = "1")
    public ResponseEntity<Void> cancelReservation(@PathVariable UUID workorderLineId) {
        reservationService.cancelReservation(workorderLineId);
        return ResponseEntity.noContent().build();
    }
}
