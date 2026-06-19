package com.positivity.customer.service;

import com.positivity.customer.internal.dto.CreateVehicleForPartyRequest;
import com.positivity.customer.internal.dto.VehicleTransferRequest;
import com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.shared.dto.VehicleResponse;
import java.util.Optional;
import java.util.UUID;

public interface CrmVehicleService {

    /**
     * Creates a new vehicle and associates it with a customer.
     */
    VehicleResponse createVehicle(UUID customerId, CreateVehicleForPartyRequest request);

    /**
     * Retrieves a vehicle for a specific customer.
     */
    Optional<VehicleResponse> getVehicleForCustomer(UUID customerId, UUID vehicleId);

    /**
     * Updates a vehicle for a customer.
     */
    VehicleResponse updateVehicle(UUID customerId, CreateVehicleForPartyRequest request, UUID vehicleId);

    /**
     * Deletes (deactivates) a vehicle for a customer.
     */
    void deleteVehicle(UUID customerId, UUID vehicleId);

    /**
     * Transfers a vehicle from one customer to another.
     */
    VehicleResponse transferVehicle(UUID sourceCustomerId, UUID vehicleId, VehicleTransferRequest request);

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
