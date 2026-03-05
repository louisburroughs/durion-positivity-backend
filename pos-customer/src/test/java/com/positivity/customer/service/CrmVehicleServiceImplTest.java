package com.positivity.customer.service;

import com.positivity.customer.internal.client.VehicleInventoryClient;
import com.positivity.customer.internal.dto.CreateVehicleForPartyRequest;
import com.positivity.customer.internal.dto.VehicleTransferRequest;
import com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.service.CrmVehicleServiceImpl;
import com.positivity.shared.dto.CreateVehicleRequest;
import com.positivity.shared.dto.VehicleResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrmVehicleServiceImplTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private VehicleInventoryClient vehicleInventoryClient;
    @Mock
    private PersonPartyRepository personPartyRepository;
    @Mock
    private CommercialPartyRepository commercialPartyRepository;

    private CrmVehicleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CrmVehicleServiceImpl(TEST_CLOCK, vehicleInventoryClient, personPartyRepository,
                commercialPartyRepository);
    }

    private CommercialParty commercialParty(UUID id) {
        CommercialParty party = new CommercialParty();
        party.setPartyId(id);
        party.setPartyNumber("PARTY-" + id.toString().substring(0, 8));
        party.setLegalName("Acme Fleet");
        party.setDisplayName("Acme");
        party.setPartyType(PartyType.COMMERCIAL);
        party.setVehicleVins(new HashSet<>());
        return party;
    }

    private PersonParty personParty(UUID id) {
        PersonParty party = new PersonParty();
        party.setPartyId(id);
        party.setFirstName("John");
        party.setLastName("Doe");
        party.setVehicleVins(new HashSet<>());
        return party;
    }

    private VehicleResponse vehicle(UUID vehicleId, String vin) {
        return VehicleResponse.builder()
                .vehicleId(vehicleId)
                .vin(vin)
                .unitNumber("U-1")
                .description("Truck")
                .licensePlate("ABC-123")
                .licensePlateJurisdiction("TX")
                .year(2022)
                .make("Ford")
                .model("F-150")
                .trim("XLT")
                .build();
    }

    @Test
    void createVehicle_associatesVin_andSavesCommercialParty() {
        UUID customerId = UUID.randomUUID();
        CommercialParty party = commercialParty(customerId);
        CreateVehicleForPartyRequest request = CreateVehicleForPartyRequest.builder()
                .vinNumber("VIN-001")
                .unitNumber("U-9")
                .description("Service Van")
                .licensePlate("PLATE")
                .licensePlateRegion("CA")
                .build();

        when(personPartyRepository.findById(customerId)).thenReturn(Optional.empty());
        when(commercialPartyRepository.findById(customerId)).thenReturn(Optional.of(party));
        when(vehicleInventoryClient.createVehicle(any(CreateVehicleRequest.class)))
                .thenReturn(vehicle(UUID.randomUUID(), "VIN-001"));

        VehicleResponse result = service.createVehicle(customerId, request);

        assertThat(result).isNotNull();
        assertThat(party.getVehicleVins()).contains("VIN-001");
        verify(commercialPartyRepository).save(party);
    }

    @Test
    void createVehicle_doesNotSaveParty_whenClientReturnsNull() {
        UUID customerId = UUID.randomUUID();
        CommercialParty party = commercialParty(customerId);

        when(personPartyRepository.findById(customerId)).thenReturn(Optional.empty());
        when(commercialPartyRepository.findById(customerId)).thenReturn(Optional.of(party));
        when(vehicleInventoryClient.createVehicle(any(CreateVehicleRequest.class))).thenReturn(null);

        VehicleResponse result = service.createVehicle(customerId, CreateVehicleForPartyRequest.builder()
                .vinNumber("VIN-NULL")
                .build());

        assertThat(result).isNull();
        verify(commercialPartyRepository, never()).save(any());
    }

    @Test
    void getVehicleForCustomer_returnsEmpty_whenVinNotOwned() {
        UUID customerId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        CommercialParty party = commercialParty(customerId);

        when(personPartyRepository.findById(customerId)).thenReturn(Optional.empty());
        when(commercialPartyRepository.findById(customerId)).thenReturn(Optional.of(party));
        when(vehicleInventoryClient.getVehicle(vehicleId)).thenReturn(Optional.of(vehicle(vehicleId, "VIN-X")));

        Optional<VehicleResponse> result = service.getVehicleForCustomer(customerId, vehicleId);

        assertThat(result).isEmpty();
    }

    @Test
    void updateVehicle_mergesRequestWithExistingData() {
        UUID customerId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        CommercialParty party = commercialParty(customerId);
        party.getVehicleVins().add("VIN-OLD");

        VehicleResponse existing = vehicle(vehicleId, "VIN-OLD");
        CreateVehicleForPartyRequest request = CreateVehicleForPartyRequest.builder()
                .vinNumber("VIN-NEW")
                .build();

        when(personPartyRepository.findById(customerId)).thenReturn(Optional.empty());
        when(commercialPartyRepository.findById(customerId)).thenReturn(Optional.of(party));
        when(vehicleInventoryClient.getVehicle(vehicleId)).thenReturn(Optional.of(existing));
        when(vehicleInventoryClient.updateVehicle(eq(vehicleId), any(CreateVehicleRequest.class)))
                .thenReturn(vehicle(vehicleId, "VIN-NEW"));

        VehicleResponse result = service.updateVehicle(customerId, request, vehicleId);

        assertThat(result.getVin()).isEqualTo("VIN-NEW");

        ArgumentCaptor<CreateVehicleRequest> captor = ArgumentCaptor.forClass(CreateVehicleRequest.class);
        verify(vehicleInventoryClient).updateVehicle(eq(vehicleId), captor.capture());
        assertThat(captor.getValue().getVin()).isEqualTo("VIN-NEW");
        assertThat(captor.getValue().getDescription()).isEqualTo("Truck");
    }

    @Test
    void updateVehicle_throws_whenVehicleNotOwned() {
        UUID customerId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        CommercialParty party = commercialParty(customerId);

        when(personPartyRepository.findById(customerId)).thenReturn(Optional.empty());
        when(commercialPartyRepository.findById(customerId)).thenReturn(Optional.of(party));
        when(vehicleInventoryClient.getVehicle(vehicleId)).thenReturn(Optional.of(vehicle(vehicleId, "VIN-OTHER")));

        assertThatThrownBy(
                () -> service.updateVehicle(customerId, CreateVehicleForPartyRequest.builder().build(), vehicleId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to customer");
    }

    @Test
    void deleteVehicle_removesVinAndSaves() {
        UUID customerId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        CommercialParty party = commercialParty(customerId);
        party.getVehicleVins().add("VIN-1");

        when(personPartyRepository.findById(customerId)).thenReturn(Optional.empty());
        when(commercialPartyRepository.findById(customerId)).thenReturn(Optional.of(party));
        when(vehicleInventoryClient.getVehicle(vehicleId)).thenReturn(Optional.of(vehicle(vehicleId, "VIN-1")));

        service.deleteVehicle(customerId, vehicleId);

        verify(vehicleInventoryClient).deleteVehicle(vehicleId);
        verify(commercialPartyRepository).save(party);
        assertThat(party.getVehicleVins()).doesNotContain("VIN-1");
    }

    @Test
    void transferVehicle_movesVinBetweenParties_andUpdatesInventoryAccount() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();

        CommercialParty source = commercialParty(sourceId);
        CommercialParty target = commercialParty(targetId);
        source.getVehicleVins().add("VIN-T");

        VehicleTransferRequest transferRequest = VehicleTransferRequest.builder()
                .targetCustomerId(targetId.toString())
                .notes("Ownership change")
                .build();

        when(personPartyRepository.findById(sourceId)).thenReturn(Optional.empty());
        when(personPartyRepository.findById(targetId)).thenReturn(Optional.empty());
        when(commercialPartyRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(commercialPartyRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(vehicleInventoryClient.getVehicle(vehicleId)).thenReturn(Optional.of(vehicle(vehicleId, "VIN-T")));
        when(vehicleInventoryClient.updateVehicle(eq(vehicleId), any(CreateVehicleRequest.class)))
                .thenReturn(vehicle(vehicleId, "VIN-T"));

        VehicleResponse result = service.transferVehicle(sourceId, vehicleId, transferRequest);

        assertThat(result).isNotNull();
        assertThat(source.getVehicleVins()).doesNotContain("VIN-T");
        assertThat(target.getVehicleVins()).contains("VIN-T");
        verify(commercialPartyRepository).save(source);
        verify(commercialPartyRepository).save(target);

        ArgumentCaptor<CreateVehicleRequest> captor = ArgumentCaptor.forClass(CreateVehicleRequest.class);
        verify(vehicleInventoryClient).updateVehicle(eq(vehicleId), captor.capture());
        assertThat(captor.getValue().getAccountId()).isEqualTo(targetId);
    }

    @Test
    void transferVehicle_throwsOnInvalidTargetId() {
        VehicleTransferRequest badRequest = VehicleTransferRequest.builder()
                .targetCustomerId("not-a-uuid")
                .build();

        assertThatThrownBy(() -> service.transferVehicle(UUID.randomUUID(), UUID.randomUUID(), badRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid customer ID format");
    }

    @Test
    void findPartyByVehicleId_returnsNull_whenVehicleMissing() {
        UUID vehicleId = UUID.randomUUID();
        when(vehicleInventoryClient.getVehicle(vehicleId)).thenReturn(Optional.empty());

        CommercialParty result = service.findPartyByVehicleId(vehicleId);

        assertThat(result).isNull();
    }

    @Test
    void findPartyByVehicleId_returnsOwner_whenVinMatchesCommercialParty() {
        UUID vehicleId = UUID.randomUUID();
        CommercialParty owner = commercialParty(UUID.randomUUID());
        owner.getVehicleVins().add("VIN-OWNER");

        when(vehicleInventoryClient.getVehicle(vehicleId)).thenReturn(Optional.of(vehicle(vehicleId, "VIN-OWNER")));
        when(commercialPartyRepository.findAll()).thenReturn(List.of(owner));

        CommercialParty result = service.findPartyByVehicleId(vehicleId);

        assertThat(result).isSameAs(owner);
    }

    @Test
    void fetchVehicleSummaryByVin_returnsSummary_whenVehicleExists() {
        UUID vehicleId = UUID.randomUUID();
        VehicleResponse vehicle = vehicle(vehicleId, "VIN-SUM");
        when(vehicleInventoryClient.getVehicleByVin("VIN-SUM")).thenReturn(Optional.of(vehicle));

        CrmSnapshotDTO.VehicleSummary summary = service.fetchVehicleSummaryByVin("VIN-SUM");

        assertThat(summary).isNotNull();
        assertThat(summary.getVehicleId()).isEqualTo(vehicleId.toString());
        assertThat(summary.getVin()).isEqualTo("VIN-SUM");
    }

    @Test
    void fetchVehicleSummaryByVin_returnsNull_whenMappingFails() {
        VehicleResponse malformed = VehicleResponse.builder().vin("VIN-BAD").build();
        when(vehicleInventoryClient.getVehicleByVin("VIN-BAD")).thenReturn(Optional.of(malformed));

        CrmSnapshotDTO.VehicleSummary summary = service.fetchVehicleSummaryByVin("VIN-BAD");

        assertThat(summary).isNull();
    }

    @Test
    void buildSnapshotForVehicleOwner_andCollectVehicles_filtersMissingVehicles() {
        UUID ownerId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();

        CommercialParty owner = commercialParty(ownerId);
        owner.getVehicleVins().add("VIN-OK");
        owner.getVehicleVins().add("VIN-MISS");
        owner.setDisplayName(null);
        owner.setLegalName("Fallback Name");

        when(vehicleInventoryClient.getVehicle(vehicleId)).thenReturn(Optional.of(vehicle(vehicleId, "VIN-OK")));
        when(commercialPartyRepository.findAll()).thenReturn(List.of(owner));
        when(vehicleInventoryClient.getVehicleByVin("VIN-OK"))
                .thenReturn(Optional.of(vehicle(UUID.randomUUID(), "VIN-OK")));
        when(vehicleInventoryClient.getVehicleByVin("VIN-MISS")).thenReturn(Optional.empty());

        CrmSnapshotDTO snapshot = service.buildSnapshotForVehicleOwner(vehicleId);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.getAccount().getAccountName()).isEqualTo("Fallback Name");
        assertThat(snapshot.getVehicles()).hasSize(1);
        assertThat(snapshot.getVehicles().getFirst().getVin()).isEqualTo("VIN-OK");
    }

    @Test
    void createVehicle_savesPersonParty_whenCustomerIsPerson() {
        UUID customerId = UUID.randomUUID();
        PersonParty person = personParty(customerId);
        when(personPartyRepository.findById(customerId)).thenReturn(Optional.of(person));
        when(vehicleInventoryClient.createVehicle(any(CreateVehicleRequest.class)))
                .thenReturn(vehicle(UUID.randomUUID(), "VIN-PER"));

        service.createVehicle(customerId, CreateVehicleForPartyRequest.builder().vinNumber("VIN-PER").build());

        verify(personPartyRepository).save(person);
        verify(commercialPartyRepository, never()).save(any());
    }
}
