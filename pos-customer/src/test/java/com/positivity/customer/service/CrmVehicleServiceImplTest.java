package com.positivity.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.ExtVehicle;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.ExtVehicleRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.service.CrmVehicleServiceImpl;
import com.positivity.shared.dto.VehicleResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CrmVehicleServiceImpl} after ADR-0044 (#843): all vehicle reads are
 * served from the {@code ext_vehicle} replica; there is no synchronous call to
 * pos-vehicle-inventory.
 */
@ExtendWith(MockitoExtension.class)
class CrmVehicleServiceImplTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final String VIN = "1HGBH41JXMN109186";

    @Mock
    private ExtVehicleRepository extVehicleRepository;

    @Mock
    private PersonPartyRepository personPartyRepository;

    @Mock
    private CommercialPartyRepository commercialPartyRepository;

    private CrmVehicleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CrmVehicleServiceImpl(
                TEST_CLOCK, extVehicleRepository, personPartyRepository, commercialPartyRepository);
    }

    private CommercialParty commercialParty(UUID id) {
        CommercialParty p = new CommercialParty();
        p.setPartyId(id);
        p.setCustomerNumber("CUST-001");
        p.setLegalName("Acme Corp");
        p.setPartyType(PartyType.COMMERCIAL);
        return p;
    }

    private ExtVehicle vehicle(UUID vehicleId, UUID accountId, String vin) {
        return ExtVehicle.builder()
                .vehicleId(vehicleId)
                .accountId(accountId)
                .vin(vin)
                .vinNormalized(vin)
                .make("Honda")
                .model("Accord")
                .year(2022)
                .licensePlate("ABC-123")
                .active(true)
                .aggregateVersion(3L)
                .updatedAt(Instant.parse("2024-01-01T00:00:00Z"))
                .build();
    }

    @Test
    void getVehicleForCustomer_returnsVehicle_whenVinOwnedByCustomer() {
        CommercialParty party = commercialParty(CUSTOMER_ID);
        party.getVehicleVins().add(VIN);
        when(personPartyRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());
        when(commercialPartyRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(party));
        when(extVehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicle(VEHICLE_ID, CUSTOMER_ID, VIN)));

        Optional<VehicleResponse> result = service.getVehicleForCustomer(CUSTOMER_ID, VEHICLE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getVehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(result.get().getVin()).isEqualTo(VIN);
        assertThat(result.get().getVersion()).isEqualTo(3L);
    }

    @Test
    void getVehicleForCustomer_returnsEmpty_whenVinNotOwned() {
        CommercialParty party = commercialParty(CUSTOMER_ID);
        when(personPartyRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());
        when(commercialPartyRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(party));
        when(extVehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicle(VEHICLE_ID, CUSTOMER_ID, VIN)));

        Optional<VehicleResponse> result = service.getVehicleForCustomer(CUSTOMER_ID, VEHICLE_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void getVehicleForCustomer_throws_whenCustomerUnknown() {
        when(personPartyRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());
        when(commercialPartyRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVehicleForCustomer(CUSTOMER_ID, VEHICLE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Customer not found");
    }

    @Test
    void findPartyByVehicleId_returnsNull_whenVehicleMissing() {
        when(extVehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.empty());

        assertThat(service.findPartyByVehicleId(VEHICLE_ID)).isNull();
    }

    @Test
    void findPartyByVehicleId_returnsOwner_whenVinMatchesCommercialParty() {
        CommercialParty party = commercialParty(CUSTOMER_ID);
        party.getVehicleVins().add(VIN);
        when(extVehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicle(VEHICLE_ID, CUSTOMER_ID, VIN)));
        when(commercialPartyRepository.findByVehicleVin(VIN)).thenReturn(List.of(party));

        CommercialParty result = service.findPartyByVehicleId(VEHICLE_ID);

        assertThat(result).isNotNull();
        assertThat(result.getPartyId()).isEqualTo(CUSTOMER_ID);
    }

    @Test
    void fetchVehicleSummaryByVin_returnsSummary_fromReplica() {
        when(extVehicleRepository.findByVinNormalizedAndActiveTrue(VIN))
                .thenReturn(Optional.of(vehicle(VEHICLE_ID, CUSTOMER_ID, VIN)));

        CrmSnapshotDTO.VehicleSummary summary = service.fetchVehicleSummaryByVin(VIN);

        assertThat(summary).isNotNull();
        assertThat(summary.getVehicleId()).isEqualTo(VEHICLE_ID.toString());
        assertThat(summary.getVin()).isEqualTo(VIN);
        assertThat(summary.getMake()).isEqualTo("Honda");
        assertThat(summary.getModel()).isEqualTo("Accord");
        assertThat(summary.getYear()).isEqualTo(2022);
    }

    @Test
    void fetchVehicleSummaryByVin_normalizesVinBeforeLookup() {
        when(extVehicleRepository.findByVinNormalizedAndActiveTrue(VIN))
                .thenReturn(Optional.of(vehicle(VEHICLE_ID, CUSTOMER_ID, VIN)));

        CrmSnapshotDTO.VehicleSummary summary = service.fetchVehicleSummaryByVin("  " + VIN.toLowerCase() + " ");

        assertThat(summary).isNotNull();
        assertThat(summary.getVin()).isEqualTo(VIN);
    }

    @Test
    void fetchVehicleSummaryByVin_returnsNull_whenReplicaHasNoRow() {
        when(extVehicleRepository.findByVinNormalizedAndActiveTrue(VIN)).thenReturn(Optional.empty());

        assertThat(service.fetchVehicleSummaryByVin(VIN)).isNull();
    }

    @Test
    void buildSnapshotForVehicleOwner_andCollectVehicles_filtersMissingVehicles() {
        String otherVin = "2HGBH41JXMN109187";
        CommercialParty party = commercialParty(CUSTOMER_ID);
        party.getVehicleVins().add(VIN);
        party.getVehicleVins().add(otherVin);
        when(extVehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicle(VEHICLE_ID, CUSTOMER_ID, VIN)));
        when(commercialPartyRepository.findByVehicleVin(VIN)).thenReturn(List.of(party));
        when(extVehicleRepository.findByVinNormalizedAndActiveTrue(VIN))
                .thenReturn(Optional.of(vehicle(VEHICLE_ID, CUSTOMER_ID, VIN)));
        // The second VIN is not in the replica yet (event not consumed) — it is dropped.
        when(extVehicleRepository.findByVinNormalizedAndActiveTrue(otherVin)).thenReturn(Optional.empty());

        CrmSnapshotDTO snapshot = service.buildSnapshotForVehicleOwner(VEHICLE_ID);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.getAccount().getPartyId()).isEqualTo(CUSTOMER_ID.toString());
        assertThat(snapshot.getVehicles()).hasSize(1);
        assertThat(snapshot.getVehicles().getFirst().getVin()).isEqualTo(VIN);
    }

    @Test
    void buildSnapshotForVehicleOwner_returnsNull_whenNoOwner() {
        when(extVehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicle(VEHICLE_ID, CUSTOMER_ID, VIN)));
        when(commercialPartyRepository.findByVehicleVin(VIN)).thenReturn(List.of());

        assertThat(service.buildSnapshotForVehicleOwner(VEHICLE_ID)).isNull();
    }

    @Test
    void findVehiclesForCustomer_returnsEmptyOptional_whenCustomerUnknown() {
        when(personPartyRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());
        when(commercialPartyRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThat(service.findVehiclesForCustomer(CUSTOMER_ID)).isEmpty();
    }

    @Test
    void findVehiclesForCustomer_resolvesPersonPartyVins_fromReplica() {
        PersonParty person = new PersonParty();
        person.setPartyId(CUSTOMER_ID);
        person.getVehicleVins().add(VIN);
        when(personPartyRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(person));
        when(extVehicleRepository.findByVinNormalizedAndActiveTrue(VIN))
                .thenReturn(Optional.of(vehicle(VEHICLE_ID, CUSTOMER_ID, VIN)));

        Optional<List<CrmSnapshotDTO.VehicleSummary>> result = service.findVehiclesForCustomer(CUSTOMER_ID);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().getFirst().getVehicleId()).isEqualTo(VEHICLE_ID.toString());
    }
}
