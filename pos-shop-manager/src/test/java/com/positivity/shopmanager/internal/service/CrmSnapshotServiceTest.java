package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.entity.ExtCustomerPartyReplica;
import com.positivity.shopmanager.internal.entity.ExtVehicleReplica;
import com.positivity.shopmanager.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtVehicleReplicaRepository;
import com.positivity.web.common.ReplicationPendingException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** #1994: a customer or vehicle absent from its replica has not replicated yet; it is not a 404. */
class CrmSnapshotServiceTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("01960003-0000-7000-8000-0000000000a1");
    private static final UUID VEHICLE_ID = UUID.fromString("01960003-0000-7000-8000-0000000000b2");

    private final ExtCustomerPartyReplicaRepository customers = mock(ExtCustomerPartyReplicaRepository.class);
    private final ExtVehicleReplicaRepository vehicles = mock(ExtVehicleReplicaRepository.class);
    private final CrmSnapshotService service = new CrmSnapshotService(customers, vehicles);

    @Test
    @DisplayName("a customer with no ext_customer_party row is 503 CRM_REPLICATION_PENDING naming the awaited id")
    void missingCustomerIsReplicationPending() {
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCustomerById(CUSTOMER_ID))
                .isInstanceOfSatisfying(ReplicationPendingException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("CRM_REPLICATION_PENDING");
                    assertThat(e.getReferenceId()).isEqualTo(CUSTOMER_ID);
                    assertThat(AnnotatedElementUtils.findMergedAnnotation(e.getClass(), ResponseStatus.class)
                                    .code())
                            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
    }

    @Test
    @DisplayName("a vehicle with no ext_vehicle row is 503 CRM_REPLICATION_PENDING naming the awaited id")
    void missingVehicleIsReplicationPending() {
        when(vehicles.findById(VEHICLE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVehicleById(VEHICLE_ID))
                .isInstanceOfSatisfying(ReplicationPendingException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("CRM_REPLICATION_PENDING");
                    assertThat(e.getReferenceId()).isEqualTo(VEHICLE_ID);
                });
    }

    @Test
    @DisplayName("a replicated customer and vehicle still build their snapshots")
    void presentRowsBuildSnapshots() {
        ExtCustomerPartyReplica party = mock(ExtCustomerPartyReplica.class);
        when(party.getPartyId()).thenReturn(CUSTOMER_ID);
        when(party.getDisplayName()).thenReturn("Acme Fleet");
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(party));
        ExtVehicleReplica vehicle = mock(ExtVehicleReplica.class);
        when(vehicle.getVehicleId()).thenReturn(VEHICLE_ID);
        when(vehicle.getAccountId()).thenReturn(CUSTOMER_ID);
        when(vehicle.isActive()).thenReturn(true);
        when(vehicles.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicle));

        Map<String, Object> customerSnapshot = service.getCustomerById(CUSTOMER_ID);
        Map<String, Object> vehicleSnapshot = service.getVehicleById(VEHICLE_ID);

        assertThat(customerSnapshot).containsEntry("partyId", CUSTOMER_ID.toString());
        assertThat(customerSnapshot).containsEntry("displayName", "Acme Fleet");
        assertThat(vehicleSnapshot).containsEntry("ownerCustomerId", CUSTOMER_ID.toString());
        assertThat(vehicleSnapshot).containsEntry("active", true);
    }
}
