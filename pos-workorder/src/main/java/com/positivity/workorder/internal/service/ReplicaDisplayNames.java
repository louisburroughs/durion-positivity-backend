package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.entity.ExtCustomerPartyReplica;
import com.positivity.workorder.internal.entity.ExtVehicleReplica;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Display strings built from the {@code ext_vehicle} / {@code ext_customer_party} replicas
 * (ADR-0044 §6), shared by every workorder-facing view that names a vehicle or a customer.
 *
 * <p>The dispatch dashboard ({@link DashboardServiceImpl}, #1656) and the workorder detail view
 * ({@link WorkorderDetailServiceImpl}, #2016) both need the same two strings from the same two
 * replicas; this is the one place the formatting rule lives so the two views cannot drift.
 */
final class ReplicaDisplayNames {

    private ReplicaDisplayNames() {}

    /**
     * A vehicle's display description: unit number, plate and VIN, whichever are known, joined
     * with " · ". {@code null} when the vehicle is not replicated or none of the three fields is
     * set — a placeholder built from the bare id would be indistinguishable from a real value.
     */
    static @Nullable String vehicleDescription(@Nullable ExtVehicleReplica vehicle) {
        if (vehicle == null) {
            return null;
        }
        String description = Stream.of(vehicle.getUnitNumber(), vehicle.getLicensePlate(), vehicle.getVin())
                .filter(part -> part != null && !part.isBlank())
                .map(String::strip)
                .collect(Collectors.joining(" · "));
        return description.isEmpty() ? null : description;
    }

    /**
     * A customer party's display name. {@code null} when the party is not replicated or its name
     * has not arrived yet.
     */
    static @Nullable String customerName(@Nullable ExtCustomerPartyReplica party) {
        if (party == null
                || party.getDisplayName() == null
                || party.getDisplayName().isBlank()) {
            return null;
        }
        return party.getDisplayName().strip();
    }
}
