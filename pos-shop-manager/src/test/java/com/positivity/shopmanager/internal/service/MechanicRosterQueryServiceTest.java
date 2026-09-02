package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.dto.LocationTechnicianRosterEntryResponse;
import com.positivity.shopmanager.internal.dto.MechanicRosterEntryResponse;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.MechanicSkill;
import com.positivity.shopmanager.internal.entity.Shop;
import com.positivity.shopmanager.internal.entity.Technician;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.repository.MechanicSkillRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import com.positivity.shopmanager.internal.repository.TechnicianRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MechanicRosterQueryServiceTest {

    private static final UUID MECHANIC_ID = UUID.fromString("01960011-0000-7000-8000-000000000001");
    private static final UUID PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000002");
    private static final UUID LOCATION_ID = UUID.fromString("01960011-0000-7000-8000-000000000003");
    private static final UUID TECHNICIAN_ID = UUID.fromString("01960011-0000-7000-8000-000000000004");

    @Mock
    private MechanicRepository mechanicRepository;

    @Mock
    private MechanicSkillRepository mechanicSkillRepository;

    @Mock
    private TechnicianRepository technicianRepository;

    @Mock
    private ShopRepository shopRepository;

    private MechanicRosterQueryService service;

    @BeforeEach
    void setUp() {
        service = new MechanicRosterQueryServiceImpl(
                mechanicRepository, mechanicSkillRepository, technicianRepository, shopRepository);
    }

    @Test
    void listMechanicsDefaultsToActiveAndMapsSkills() {
        Pageable pageable = PageRequest.of(0, 20);
        Mechanic mechanic = Mechanic.builder()
                .mechanicId(MECHANIC_ID)
                .personId(PERSON_ID.toString())
                .firstName("Ada")
                .lastName("Lovelace")
                .status(MechanicStatus.ACTIVE)
                .hireDate(LocalDate.parse("2025-01-15"))
                .lastSyncedAt(Instant.parse("2026-08-31T15:30:00Z"))
                .build();
        MechanicSkill skill = MechanicSkill.builder()
                .mechanic(mechanic)
                .skillCode("ALIGNMENT")
                .build();

        when(mechanicRepository.findRoster(MechanicStatus.ACTIVE, null, pageable))
                .thenReturn(new PageImpl<>(List.of(mechanic), pageable, 1));
        when(mechanicSkillRepository.findAllByMechanicIdIn(List.of(MECHANIC_ID)))
                .thenReturn(List.of(skill));

        Page<MechanicRosterEntryResponse> result = service.listMechanics(null, null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).singleElement().satisfies(entry -> {
            assertThat(entry.getMechanicId()).isEqualTo(MECHANIC_ID);
            assertThat(entry.getPersonId()).isEqualTo(PERSON_ID);
            assertThat(entry.getFirstName()).isEqualTo("Ada");
            assertThat(entry.getLastName()).isEqualTo("Lovelace");
            assertThat(entry.getStatus()).isEqualTo(MechanicStatus.ACTIVE);
            assertThat(entry.getSkills()).containsExactly("ALIGNMENT");
        });
    }

    @Test
    void listLocationTechniciansReturnsOnlyLocationAssignmentsWithMechanicDetails() {
        Pageable pageable = PageRequest.of(0, 20);
        Shop shop = Shop.builder().id(LOCATION_ID).build();
        Technician technician = Technician.builder()
                .id(TECHNICIAN_ID)
                .personId(PERSON_ID)
                .shop(shop)
                .build();
        Mechanic mechanic = Mechanic.builder()
                .mechanicId(MECHANIC_ID)
                .personId(PERSON_ID.toString())
                .firstName("Ada")
                .lastName("Lovelace")
                .status(MechanicStatus.ACTIVE)
                .build();

        when(shopRepository.existsById(LOCATION_ID)).thenReturn(true);
        when(technicianRepository.findRosterByLocation(LOCATION_ID, MechanicStatus.ACTIVE, null, pageable))
                .thenReturn(new PageImpl<>(List.of(technician), pageable, 1));
        when(mechanicRepository.findAllByPersonIdIn(List.of(PERSON_ID.toString())))
                .thenReturn(List.of(mechanic));
        when(mechanicSkillRepository.findAllByMechanicIdIn(List.of(MECHANIC_ID)))
                .thenReturn(List.of());

        Page<LocationTechnicianRosterEntryResponse> result =
                service.listLocationTechnicians(LOCATION_ID, null, null, pageable);

        assertThat(result.getContent()).singleElement().satisfies(entry -> {
            assertThat(entry.getTechnicianId()).isEqualTo(TECHNICIAN_ID);
            assertThat(entry.getLocationId()).isEqualTo(LOCATION_ID);
            assertThat(entry.getMechanicId()).isEqualTo(MECHANIC_ID);
            assertThat(entry.getPersonId()).isEqualTo(PERSON_ID);
        });
    }

    @Test
    void listLocationTechniciansReturnsNotFoundForUnknownLocation() {
        when(shopRepository.existsById(LOCATION_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.listLocationTechnicians(LOCATION_ID, null, null, PageRequest.of(0, 20)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void listLocationTechniciansReturnsEmptyPageWithoutEnrichmentQueries() {
        Pageable pageable = PageRequest.of(0, 20);
        when(shopRepository.existsById(LOCATION_ID)).thenReturn(true);
        when(technicianRepository.findRosterByLocation(LOCATION_ID, MechanicStatus.INACTIVE, "BRAKES", pageable))
                .thenReturn(Page.empty(pageable));

        Page<LocationTechnicianRosterEntryResponse> result =
                service.listLocationTechnicians(LOCATION_ID, MechanicStatus.INACTIVE, "BRAKES", pageable);

        assertThat(result).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        verifyNoInteractions(mechanicRepository, mechanicSkillRepository);
    }

    @Test
    void listLocationTechniciansUsesFixedRepositoryOrdering() {
        Pageable requestedPageable = PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "lastName"));
        Pageable repositoryPageable = PageRequest.of(1, 5);
        when(shopRepository.existsById(LOCATION_ID)).thenReturn(true);
        when(technicianRepository.findRosterByLocation(LOCATION_ID, MechanicStatus.ACTIVE, null, repositoryPageable))
                .thenReturn(Page.empty(repositoryPageable));

        Page<LocationTechnicianRosterEntryResponse> result =
                service.listLocationTechnicians(LOCATION_ID, null, null, requestedPageable);

        assertThat(result.getPageable()).isEqualTo(repositoryPageable);
    }
}
