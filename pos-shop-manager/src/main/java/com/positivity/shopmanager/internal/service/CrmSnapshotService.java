package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.entity.ExtCustomerPartyReplica;
import com.positivity.shopmanager.internal.entity.ExtVehicleReplica;
import com.positivity.shopmanager.internal.exception.CrmCustomerNotFoundException;
import com.positivity.shopmanager.internal.exception.CrmVehicleNotFoundException;
import com.positivity.shopmanager.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtVehicleReplicaRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds appointment customer/vehicle snapshots from the local {@code ext_customer_party} and
 * {@code ext_vehicle} replicas (ADR-0044 §6, #891) — the replacement for the retired synchronous
 * {@code CrmCustomerClient}/{@code CrmVehicleClient}. An id with no replica row maps to the same
 * not-found exceptions the HTTP 404s used to raise; there is no "CRM unavailable" failure mode
 * anymore because the read is local.
 */
@Service
@RequiredArgsConstructor
public class CrmSnapshotService {

    private final ExtCustomerPartyReplicaRepository extCustomerPartyReplicaRepository;
    private final ExtVehicleReplicaRepository extVehicleReplicaRepository;

    /** Customer snapshot map persisted on the appointment for audit/display. */
    @Transactional(readOnly = true)
    public @NonNull Map<String, Object> getCustomerById(@NonNull UUID customerId) {
        ExtCustomerPartyReplica party = extCustomerPartyReplicaRepository
                .findById(customerId)
                .orElseThrow(() -> new CrmCustomerNotFoundException(customerId));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        put(snapshot, "partyId", party.getPartyId().toString());
        put(snapshot, "displayName", party.getDisplayName());
        put(snapshot, "customerNumber", party.getCustomerNumber());
        put(snapshot, "status", party.getStatus());
        put(snapshot, "partyType", party.getPartyType());
        return snapshot;
    }

    /**
     * Vehicle snapshot map persisted on the appointment. {@code ownerCustomerId} carries the
     * owning party id the appointment flow validates against the requested customer.
     */
    @Transactional(readOnly = true)
    public @NonNull Map<String, Object> getVehicleById(@NonNull UUID vehicleId) {
        ExtVehicleReplica vehicle = extVehicleReplicaRepository
                .findById(vehicleId)
                .orElseThrow(() -> new CrmVehicleNotFoundException(vehicleId));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        put(snapshot, "vehicleId", vehicle.getVehicleId().toString());
        put(snapshot, "ownerCustomerId", vehicle.getAccountId().toString());
        put(snapshot, "vin", vehicle.getVin());
        put(snapshot, "unitNumber", vehicle.getUnitNumber());
        put(snapshot, "description", vehicle.getDescription());
        put(snapshot, "licensePlate", vehicle.getLicensePlate());
        put(snapshot, "year", vehicle.getYear());
        put(snapshot, "make", vehicle.getMake());
        put(snapshot, "model", vehicle.getModel());
        snapshot.put("active", vehicle.isActive());
        return snapshot;
    }

    private static void put(@NonNull Map<String, Object> map, @NonNull String key, @Nullable Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
