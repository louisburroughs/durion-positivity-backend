package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shopmanager.internal.dto.LocationTechnicianRosterEntryResponse;
import com.positivity.shopmanager.internal.dto.MechanicRosterEntryResponse;
import com.positivity.shopmanager.internal.dto.TechnicianCredentialResponse;
import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.entity.ExtPersonCredentialReplica;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.Shop;
import com.positivity.shopmanager.internal.enums.CredentialStatus;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.enums.ShiftSource;
import com.positivity.shopmanager.internal.enums.ShiftStatus;
import com.positivity.shopmanager.internal.repository.ExtLocationReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtPersonCredentialReplicaRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

/**
 * CAP-328: rosters project credentials (with a status judged on the roster's reference date) and
 * never flatten to codes; the location roster's date is the facility's local date.
 */
@ExtendWith(MockitoExtension.class)
class MechanicRosterQueryServiceTest {

    private static final UUID MECHANIC_ID = UUID.fromString("01960011-0000-7000-8000-000000000001");
    private static final UUID PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000002");
    private static final UUID LOCATION_ID = UUID.fromString("01960011-0000-7000-8000-000000000003");
    private static final UUID CREDENTIAL_ID = UUID.fromString("01960011-0000-7000-8000-000000000031");

    /** 2026-09-16T03:30Z is still 2026-09-15 in Los Angeles: the two dates differ on purpose. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T03:30:00Z"), ZoneOffset.UTC);

    @Mock
    private MechanicRepository mechanicRepository;

    @Mock
    private ExtPersonCredentialReplicaRepository credentialRepository;

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private ExtLocationReplicaRepository locationReplicaRepository;

    private final LocationHoursParser hoursParser = new LocationHoursParser(new ObjectMapper());

    private MechanicRosterQueryService service;

    @BeforeEach
    void setUp() {
        service = new MechanicRosterQueryServiceImpl(
                mechanicRepository,
                credentialRepository,
                shopRepository,
                locationReplicaRepository,
                hoursParser,
                new LocationHoursShiftWindowService(hoursParser),
                CLOCK);
    }

    private static Mechanic ada() {
        return Mechanic.builder()
                .mechanicId(MECHANIC_ID)
                .personId(PERSON_ID)
                .firstName("Ada")
                .lastName("Lovelace")
                .status(MechanicStatus.ACTIVE)
                .hireDate(LocalDate.parse("2025-01-15"))
                .lastSyncedAt(Instant.parse("2026-08-31T15:30:00Z"))
                .build();
    }

    private static ExtPersonCredentialReplica brakes(LocalDate expiresOn, String feedStatus) {
        return ExtPersonCredentialReplica.builder()
                .credentialId(CREDENTIAL_ID)
                .personId(PERSON_ID)
                .skillId(UUID.fromString("01960011-0000-7000-8000-000000000041"))
                .skillCode("BRAKES-MEDIUM_HEAVY")
                .competenceCode("BRAKES")
                .minGvwrClass(4)
                .maxGvwrClass(8)
                .issuer("ASE")
                .sourceCode("ASE")
                .sourceCredentialCode("T4-BRAKES")
                .issuedOn(LocalDate.parse("2021-09-16"))
                .expiresOn(expiresOn)
                .proficiency(4)
                .status(feedStatus)
                .aggregateVersion(3)
                .updatedAt(Instant.parse("2026-08-31T15:30:00Z"))
                .build();
    }

    @Test
    @DisplayName(
            "the mechanic roster defaults to ACTIVE, judges credentials on the clock's date, and projects them whole")
    void listMechanicsDefaultsToActiveAndProjectsCredentials() {
        Pageable pageable = PageRequest.of(0, 20);
        when(mechanicRepository.findRoster(MechanicStatus.ACTIVE, null, LocalDate.parse("2026-09-16"), pageable))
                .thenReturn(new PageImpl<>(List.of(ada()), pageable, 1));
        when(credentialRepository.findByPersonIdInOrderByIssuedOnDesc(List.of(PERSON_ID)))
                .thenReturn(List.of(brakes(LocalDate.parse("2026-09-15"), "ACTIVE")));

        Page<MechanicRosterEntryResponse> result = service.listMechanics(null, null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).singleElement().satisfies(entry -> {
            assertThat(entry.getMechanicId()).isEqualTo(MECHANIC_ID);
            assertThat(entry.getPersonId()).isEqualTo(PERSON_ID);
            assertThat(entry.getFirstName()).isEqualTo("Ada");
            assertThat(entry.getStatus()).isEqualTo(MechanicStatus.ACTIVE);
            assertThat(entry.getCredentials()).singleElement().satisfies(credential -> {
                assertThat(credential.getCredentialId()).isEqualTo(CREDENTIAL_ID);
                assertThat(credential.getSkillCode()).isEqualTo("BRAKES-MEDIUM_HEAVY");
                assertThat(credential.getSourceCredentialCode()).isEqualTo("T4-BRAKES");
                assertThat(credential.getIssuer()).isEqualTo("ASE");
                assertThat(credential.getProficiency()).isEqualTo(4);
                assertThat(credential.getExpiresOn()).isEqualTo(LocalDate.parse("2026-09-15"));
                // Expired yesterday on the clock's date: listed, and listed as EXPIRED — not dropped,
                // and not read as held.
                assertThat(credential.getStatus()).isEqualTo(CredentialStatus.EXPIRED);
            });
        });
    }

    @Test
    @DisplayName("the location roster judges expiry on the facility's local date from the location replica")
    void listLocationTechniciansUsesTheFacilityLocalDate() {
        Pageable pageable = PageRequest.of(0, 20);
        when(shopRepository.findById(LOCATION_ID))
                .thenReturn(Optional.of(
                        Shop.builder().id(LOCATION_ID).timezone("UTC").build()));
        when(locationReplicaRepository.findById(LOCATION_ID))
                .thenReturn(Optional.of(ExtLocationReplica.builder()
                        .locationId(LOCATION_ID)
                        .timezone("America/Los_Angeles")
                        .build()));
        // Still the 15th in Los Angeles, so the roster asks the repository about the 15th.
        LocalDate facilityDate = LocalDate.parse("2026-09-15");
        when(mechanicRepository.findRosterByLocation(
                        LOCATION_ID, MechanicStatus.ACTIVE, "T4-BRAKES", facilityDate, pageable))
                .thenReturn(new PageImpl<>(List.of(ada()), pageable, 1));
        when(credentialRepository.findByPersonIdInOrderByIssuedOnDesc(List.of(PERSON_ID)))
                .thenReturn(List.of(brakes(facilityDate, "ACTIVE")));

        Page<LocationTechnicianRosterEntryResponse> result =
                service.listLocationTechnicians(LOCATION_ID, null, "T4-BRAKES", null, pageable);

        assertThat(result.getContent()).singleElement().satisfies(entry -> {
            assertThat(entry.getLocationId()).isEqualTo(LOCATION_ID);
            assertThat(entry.getMechanicId()).isEqualTo(MECHANIC_ID);
            assertThat(entry.getPersonId()).isEqualTo(PERSON_ID);
            // Expires on the facility's today: held through the day, so ACTIVE.
            assertThat(entry.getCredentials())
                    .extracting(TechnicianCredentialResponse::getStatus)
                    .containsExactly(CredentialStatus.ACTIVE);
        });
    }

    @Test
    @DisplayName("without a location replica the facility date falls back to the shop's own timezone")
    void listLocationTechniciansFallsBackToShopTimezone() {
        Pageable pageable = PageRequest.of(0, 20);
        when(shopRepository.findById(LOCATION_ID))
                .thenReturn(Optional.of(Shop.builder()
                        .id(LOCATION_ID)
                        .timezone("America/Chicago")
                        .build()));
        when(locationReplicaRepository.findById(LOCATION_ID)).thenReturn(Optional.empty());
        when(mechanicRepository.findRosterByLocation(
                        eq(LOCATION_ID), eq(MechanicStatus.ACTIVE), eq(null), eq(LocalDate.parse("2026-09-15")), any()))
                .thenReturn(Page.empty(pageable));

        service.listLocationTechnicians(LOCATION_ID, null, null, null, pageable);

        verify(mechanicRepository)
                .findRosterByLocation(
                        LOCATION_ID, MechanicStatus.ACTIVE, null, LocalDate.parse("2026-09-15"), pageable);
    }

    @Test
    @DisplayName("revoked stays revoked on the projection regardless of dates")
    void revokedCredentialProjectsAsRevoked() {
        Pageable pageable = PageRequest.of(0, 20);
        when(mechanicRepository.findRoster(MechanicStatus.ACTIVE, null, LocalDate.parse("2026-09-16"), pageable))
                .thenReturn(new PageImpl<>(List.of(ada()), pageable, 1));
        when(credentialRepository.findByPersonIdInOrderByIssuedOnDesc(List.of(PERSON_ID)))
                .thenReturn(List.of(brakes(null, "REVOKED")));

        Page<MechanicRosterEntryResponse> result = service.listMechanics(null, null, pageable);

        assertThat(result.getContent().getFirst().getCredentials())
                .extracting(TechnicianCredentialResponse::getStatus)
                .containsExactly(CredentialStatus.REVOKED);
    }

    @Test
    void listLocationTechniciansReturnsNotFoundForUnknownLocation() {
        when(shopRepository.findById(LOCATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listLocationTechnicians(LOCATION_ID, null, null, null, PageRequest.of(0, 20)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(mechanicRepository, credentialRepository);
    }

    @Test
    void listLocationTechniciansReturnsEmptyPageWithoutCredentialQueries() {
        Pageable pageable = PageRequest.of(0, 20);
        when(shopRepository.findById(LOCATION_ID))
                .thenReturn(Optional.of(Shop.builder().id(LOCATION_ID).build()));
        when(locationReplicaRepository.findById(LOCATION_ID)).thenReturn(Optional.empty());
        when(mechanicRepository.findRosterByLocation(
                        LOCATION_ID, MechanicStatus.INACTIVE, "BRAKES", LocalDate.parse("2026-09-16"), pageable))
                .thenReturn(Page.empty(pageable));

        Page<LocationTechnicianRosterEntryResponse> result =
                service.listLocationTechnicians(LOCATION_ID, MechanicStatus.INACTIVE, "BRAKES", null, pageable);

        assertThat(result).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        verifyNoInteractions(credentialRepository);
    }

    @Test
    void listLocationTechniciansUsesFixedRepositoryOrdering() {
        Pageable requestedPageable = PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "lastName"));
        Pageable repositoryPageable = PageRequest.of(1, 5);
        when(shopRepository.findById(LOCATION_ID))
                .thenReturn(Optional.of(Shop.builder().id(LOCATION_ID).build()));
        when(locationReplicaRepository.findById(LOCATION_ID)).thenReturn(Optional.empty());
        when(mechanicRepository.findRosterByLocation(
                        LOCATION_ID, MechanicStatus.ACTIVE, null, LocalDate.parse("2026-09-16"), repositoryPageable))
                .thenReturn(Page.empty(repositoryPageable));

        Page<LocationTechnicianRosterEntryResponse> result =
                service.listLocationTechnicians(LOCATION_ID, null, null, null, requestedPageable);

        assertThat(result.getPageable()).isEqualTo(repositoryPageable);
    }

    // ---- PLACEHOLDER shift window on the location roster (issue #2060) ----------------------

    private static final String WEEKDAY_HOURS = """
            [{"dayOfWeek":"MONDAY","openTime":"08:00","closeTime":"17:00"},
             {"dayOfWeek":"TUESDAY","openTime":"08:00","closeTime":"17:00"},
             {"dayOfWeek":"WEDNESDAY","openTime":"08:00","closeTime":"17:00"},
             {"dayOfWeek":"THURSDAY","openTime":"08:00","closeTime":"17:00"},
             {"dayOfWeek":"FRIDAY","openTime":"08:00","closeTime":"17:00"}]
            """;

    private static Mechanic grace() {
        return Mechanic.builder()
                .mechanicId(UUID.fromString("01960011-0000-7000-8000-000000000011"))
                .personId(UUID.fromString("01960011-0000-7000-8000-000000000012"))
                .firstName("Grace")
                .lastName("Hopper")
                .status(MechanicStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("AC1/AC7: every technician on the roster for the date carries the same location-hours window")
    void everyTechnicianReceivesTheSameDerivedWindow() {
        Pageable pageable = PageRequest.of(0, 20);
        LocalDate tuesday = LocalDate.parse("2026-09-15");
        when(shopRepository.findById(LOCATION_ID))
                .thenReturn(Optional.of(Shop.builder().id(LOCATION_ID).build()));
        when(locationReplicaRepository.findById(LOCATION_ID))
                .thenReturn(Optional.of(ExtLocationReplica.builder()
                        .locationId(LOCATION_ID)
                        .timezone("America/New_York")
                        .operatingHours(WEEKDAY_HOURS)
                        .build()));
        when(mechanicRepository.findRosterByLocation(LOCATION_ID, MechanicStatus.ACTIVE, null, tuesday, pageable))
                .thenReturn(new PageImpl<>(List.of(ada(), grace()), pageable, 2));
        when(credentialRepository.findByPersonIdInOrderByIssuedOnDesc(any())).thenReturn(List.of());

        Page<LocationTechnicianRosterEntryResponse> result =
                service.listLocationTechnicians(LOCATION_ID, null, null, tuesday, pageable);

        assertThat(result.getContent()).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.getShiftStatus()).isEqualTo(ShiftStatus.DERIVED);
            assertThat(entry.getShiftSource()).isEqualTo(ShiftSource.LOCATION_HOURS);
            // 08:00–17:00 New York on 2026-09-15 (EDT, UTC-4).
            assertThat(entry.getShiftStart()).isEqualTo(Instant.parse("2026-09-15T12:00:00Z"));
            assertThat(entry.getShiftEnd()).isEqualTo(Instant.parse("2026-09-15T21:00:00Z"));
            assertThat(entry.getShiftMinutes()).isEqualTo(540);
        });
        // Pinned explicitly: the two entries are identical in every window field, by construction.
        LocationTechnicianRosterEntryResponse first = result.getContent().get(0);
        LocationTechnicianRosterEntryResponse second = result.getContent().get(1);
        assertThat(second.getShiftStart()).isEqualTo(first.getShiftStart());
        assertThat(second.getShiftEnd()).isEqualTo(first.getShiftEnd());
        assertThat(second.getShiftMinutes()).isEqualTo(first.getShiftMinutes());
        // AC9: the requested date is also the roster's assignment/credential reference date.
        verify(mechanicRepository).findRosterByLocation(LOCATION_ID, MechanicStatus.ACTIVE, null, tuesday, pageable);
    }

    @Test
    @DisplayName("AC2/AC9: without a location replica the window is UNKNOWN and nothing else changes")
    void withoutAReplicaTheWindowIsUnknownWithNullBounds() {
        Pageable pageable = PageRequest.of(0, 20);
        when(shopRepository.findById(LOCATION_ID))
                .thenReturn(Optional.of(Shop.builder().id(LOCATION_ID).build()));
        when(locationReplicaRepository.findById(LOCATION_ID)).thenReturn(Optional.empty());
        when(mechanicRepository.findRosterByLocation(
                        LOCATION_ID, MechanicStatus.ACTIVE, null, LocalDate.parse("2026-09-16"), pageable))
                .thenReturn(new PageImpl<>(List.of(ada()), pageable, 1));
        when(credentialRepository.findByPersonIdInOrderByIssuedOnDesc(List.of(PERSON_ID)))
                .thenReturn(List.of());

        Page<LocationTechnicianRosterEntryResponse> result =
                service.listLocationTechnicians(LOCATION_ID, null, null, null, pageable);

        assertThat(result.getContent()).singleElement().satisfies(entry -> {
            assertThat(entry.getShiftStatus()).isEqualTo(ShiftStatus.UNKNOWN);
            assertThat(entry.getShiftSource()).isEqualTo(ShiftSource.LOCATION_HOURS);
            assertThat(entry.getShiftStart()).isNull();
            assertThat(entry.getShiftEnd()).isNull();
            assertThat(entry.getShiftMinutes()).isNull();
            assertThat(entry.getPersonId()).isEqualTo(PERSON_ID);
        });
    }

    @Test
    @DisplayName("AC6: an omitted date is today in the location's timezone, and the window is derived for it")
    void anOmittedDateDefaultsToTheFacilityTodayForTheWindowToo() {
        Pageable pageable = PageRequest.of(0, 20);
        when(shopRepository.findById(LOCATION_ID))
                .thenReturn(Optional.of(Shop.builder().id(LOCATION_ID).build()));
        when(locationReplicaRepository.findById(LOCATION_ID))
                .thenReturn(Optional.of(ExtLocationReplica.builder()
                        .locationId(LOCATION_ID)
                        .timezone("America/Los_Angeles")
                        .operatingHours(WEEKDAY_HOURS)
                        .build()));
        // 2026-09-16T03:30Z is still Tuesday the 15th in Los Angeles.
        LocalDate facilityToday = LocalDate.parse("2026-09-15");
        when(mechanicRepository.findRosterByLocation(LOCATION_ID, MechanicStatus.ACTIVE, null, facilityToday, pageable))
                .thenReturn(new PageImpl<>(List.of(ada()), pageable, 1));
        when(credentialRepository.findByPersonIdInOrderByIssuedOnDesc(List.of(PERSON_ID)))
                .thenReturn(List.of());

        Page<LocationTechnicianRosterEntryResponse> result =
                service.listLocationTechnicians(LOCATION_ID, null, null, null, pageable);

        assertThat(result.getContent()).singleElement().satisfies(entry -> {
            assertThat(entry.getShiftStatus()).isEqualTo(ShiftStatus.DERIVED);
            // 08:00–17:00 Los Angeles on the 15th (PDT, UTC-7), not on the UTC 16th.
            assertThat(entry.getShiftStart()).isEqualTo(Instant.parse("2026-09-15T15:00:00Z"));
            assertThat(entry.getShiftEnd()).isEqualTo(Instant.parse("2026-09-16T00:00:00Z"));
            assertThat(entry.getShiftMinutes()).isEqualTo(540);
        });
    }
}
