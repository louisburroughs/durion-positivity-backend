package com.positivity.customer.service;

import com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.shared.dto.VehicleResponse;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side vehicle queries for CRM, served from the {@code ext_vehicle} replica (ADR-0044 §6).
 * Vehicle registry writes go directly to pos-vehicle-inventory through the gateway; the
 * vehicle-party association follows via {@code vehicle.events.v1}.
 */
public interface CrmVehicleService {

    /**
     * Retrieves a vehicle for a specific customer.
     */
    Optional<VehicleResponse> getVehicleForCustomer(UUID customerId, UUID vehicleId);

    CommercialParty findPartyByVehicleId(UUID vehicleId);

    com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO.VehicleSummary fetchVehicleSummaryByVin(
            String vinCode);

    CrmSnapshotDTO buildSnapshotForVehicleOwner(UUID vehicleId);

    CrmSnapshotDTO buildSnapshotForOwnerParty(CommercialParty party);

    java.util.List<com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO.VehicleSummary> collectVehiclesForParty(
            CommercialParty party);

    /**
     * Lists vehicle summaries for any customer (person or commercial), resolved by
     * the Durion customer/party id. Returns {@link Optional#empty()} when the
     * customer does not exist (so the caller can answer 404); a present list may be
     * empty when the customer has no associated vehicles.
     *
     * @param customerId the Durion customer (party) id
     * @return optional list of vehicle summaries; empty when the customer is unknown
     */
    Optional<java.util.List<com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO.VehicleSummary>>
            findVehiclesForCustomer(UUID customerId);
}
