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
}
