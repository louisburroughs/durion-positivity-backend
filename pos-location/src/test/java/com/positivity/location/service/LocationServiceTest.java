package com.positivity.location.service;

import com.positivity.location.internal.client.PersonClient;
import com.positivity.location.internal.dto.LocationPatchRequest;
import com.positivity.location.internal.dto.LocationRequestDTO;
import com.positivity.location.internal.dto.LocationResponseDTO;
import com.positivity.location.internal.dto.LocationTypeDTO;
import com.positivity.location.internal.dto.OperatingHoursRequest;
import com.positivity.location.internal.dto.HolidayClosureRequest;
import com.positivity.location.internal.dto.PersonDTO;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.LocationParent;
import com.positivity.location.internal.entity.LocationType;
import com.positivity.location.internal.entity.ParentType;
import com.positivity.location.internal.repository.LocationParentRepository;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.LocationTypeRepository;
import com.positivity.location.internal.service.LocationServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RED unit tests for shop location create/maintain behavior.
 *
 * Issue: CAP-136 #78
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LocationServiceTest")
class LocationServiceTest {

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private LocationParentRepository locationParentRepository;

    @Mock
    private LocationTypeRepository locationTypeRepository;

    @Mock
    private PersonClient personClient;

    @InjectMocks
    private LocationServiceImpl locationService;

    @Test
    void createLocation_validPayload_returnsLocationResponse() {
        UUID locationId = UUID.randomUUID();
        LocationType locationType = LocationType.builder().id(UUID.randomUUID()).name("Shop").description("Shop type")
                .build();
        LocationRequestDTO request = validRequest("Primary Shop", "SHOP-001");
        Location saved = savedLocation(locationId, request, locationType);

        when(locationTypeRepository.findByNameIgnoreCase("Shop")).thenReturn(Optional.of(locationType));
        when(locationRepository.saveAndFlush(any(Location.class))).thenReturn(saved);

        LocationResponseDTO response = locationService.createLocation(request);

        assertThat(response.getId()).isEqualTo(locationId);
        assertThat(response.getName()).isEqualTo("Primary Shop");
        assertThat(response.getCode()).isEqualTo("SHOP-001");
        assertThat(response.isActive()).isTrue();
        assertThat(response.getType()).isNotNull();
        assertThat(response.getType().getName()).isEqualTo("Shop");
    }

    @Test
    void createLocation_invalidTimezone_throwsException() {
        LocationRequestDTO request = validRequest("Timezone Shop", "TZ-001");
        request.setTimezone("Mars/Olympus");

        assertThatThrownBy(() -> locationService.createLocation(request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException ex = (ResponseStatusException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(ex.getReason()).contains("INVALID_TIMEZONE");
                });
    }

    @Test
    void createLocation_duplicateName_throwsConflict() {
        LocationRequestDTO first = validRequest("Main Street", "LOC-100");
        LocationRequestDTO duplicateNameDifferentCase = validRequest("  main street  ", "LOC-101");
        LocationType locationType = LocationType.builder().id(UUID.randomUUID()).name("Shop").build();

        when(locationTypeRepository.findByNameIgnoreCase("Shop")).thenReturn(Optional.of(locationType));
        when(locationRepository.saveAndFlush(any(Location.class)))
                .thenReturn(savedLocation(UUID.randomUUID(), first, locationType))
                .thenReturn(savedLocation(UUID.randomUUID(), duplicateNameDifferentCase, locationType));

        locationService.createLocation(first);

        assertThatThrownBy(() -> locationService.createLocation(duplicateNameDifferentCase))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException ex = (ResponseStatusException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).contains("LOCATION_NAME_TAKEN");
                });
    }

    @Test
    void createLocation_overnightHours_throwsValidationException() {
        LocationRequestDTO request = validRequest("Overnight Shop", "OH-001");
        request.setOperatingHours(List.of(
                OperatingHoursRequest.builder()
                        .dayOfWeek("MONDAY")
                        .openTime(LocalTime.of(18, 0))
                        .closeTime(LocalTime.of(9, 0))
                        .build()));

        assertThatThrownBy(() -> locationService.createLocation(request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException ex = (ResponseStatusException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(ex.getReason()).contains("INVALID_OPERATING_HOURS");
                });
    }

    @Test
    void createLocation_serializesScheduleFieldsAsCanonicalJson() {
        LocationRequestDTO request = validRequest("Json Shop", "JSON-001");
        request.setOperatingHours(List.of(
                OperatingHoursRequest.builder().dayOfWeek("TUESDAY").openTime(LocalTime.of(9, 0))
                        .closeTime(LocalTime.of(18, 0)).build(),
                OperatingHoursRequest.builder().dayOfWeek("MONDAY").openTime(LocalTime.of(8, 30))
                        .closeTime(LocalTime.of(17, 0)).build()));
        request.setHolidayClosures(List.of(
                HolidayClosureRequest.builder().date(LocalDate.of(2026, 12, 25)).reason("Christmas").build(),
                HolidayClosureRequest.builder().date(LocalDate.of(2026, 1, 1)).reason("New Year").build()));
        LocationType locationType = LocationType.builder().id(UUID.randomUUID()).name("Shop").build();

        when(locationTypeRepository.findByNameIgnoreCase("Shop")).thenReturn(Optional.of(locationType));
        when(locationRepository.existsByNormalizedName("json shop")).thenReturn(false);
        when(locationRepository.saveAndFlush(any(Location.class))).thenAnswer(invocation -> {
            Location toSave = invocation.getArgument(0);
            toSave.setId(UUID.randomUUID());
            return toSave;
        });

        locationService.createLocation(request);

        var captor = org.mockito.ArgumentCaptor.forClass(Location.class);
        verify(locationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getOperatingHours()).isEqualTo(
                "[{\"dayOfWeek\":\"MONDAY\",\"openTime\":\"08:30:00\",\"closeTime\":\"17:00:00\"},"
                        + "{\"dayOfWeek\":\"TUESDAY\",\"openTime\":\"09:00:00\",\"closeTime\":\"18:00:00\"}]");
        assertThat(captor.getValue().getHolidayClosures()).isEqualTo(
                "[{\"date\":\"2026-01-01\",\"reason\":\"New Year\"},"
                        + "{\"date\":\"2026-12-25\",\"reason\":\"Christmas\"}]");
    }

    @Test
    void createLocation_duplicateOperatingHoursDays_throwsValidationException() {
        LocationRequestDTO request = validRequest("Duplicate Days Shop", "OH-002");
        request.setOperatingHours(List.of(
                OperatingHoursRequest.builder()
                        .dayOfWeek("MONDAY")
                        .openTime(LocalTime.of(8, 0))
                        .closeTime(LocalTime.of(17, 0))
                        .build(),
                OperatingHoursRequest.builder()
                        .dayOfWeek("MONDAY")
                        .openTime(LocalTime.of(9, 0))
                        .closeTime(LocalTime.of(18, 0))
                        .build()));

        assertThatThrownBy(() -> locationService.createLocation(request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException ex = (ResponseStatusException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(ex.getReason()).contains("INVALID_OPERATING_HOURS");
                });
    }

    @Test
    void createLocation_nameUniqueConstraintViolation_mapsToLocationNameTaken() {
        LocationRequestDTO request = validRequest("Race Name", "CODE-001");
        LocationType locationType = LocationType.builder().id(UUID.randomUUID()).name("Shop").build();

        when(locationTypeRepository.findByNameIgnoreCase("Shop")).thenReturn(Optional.of(locationType));
        when(locationRepository.existsByNormalizedName("race name")).thenReturn(false);
        when(locationRepository.saveAndFlush(any(Location.class)))
                .thenThrow(new DataIntegrityViolationException("violates uq_location_normalized_name"));

        assertThatThrownBy(() -> locationService.createLocation(request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException ex = (ResponseStatusException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).contains("LOCATION_NAME_TAKEN");
                });
    }

    @Test
    void createLocation_codeUniqueConstraintViolation_mapsToLocationCodeTaken() {
        LocationRequestDTO request = validRequest("Race Code", "CODE-002");
        LocationType locationType = LocationType.builder().id(UUID.randomUUID()).name("Shop").build();

        when(locationTypeRepository.findByNameIgnoreCase("Shop")).thenReturn(Optional.of(locationType));
        when(locationRepository.existsByNormalizedName("race code")).thenReturn(false);
        when(locationRepository.saveAndFlush(any(Location.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates location_code_key"));

        assertThatThrownBy(() -> locationService.createLocation(request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException ex = (ResponseStatusException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).contains("LOCATION_CODE_TAKEN");
                });
    }

    @Test
    void getLocation_found_returnsResponse() {
        UUID id = UUID.randomUUID();
        LocationType locationType = LocationType.builder().id(UUID.randomUUID()).name("Shop").build();
        Location location = savedLocation(id, validRequest("Get Shop", "GET-001"), locationType);

        when(locationRepository.findById(id)).thenReturn(Optional.of(location));

        Optional<LocationResponseDTO> result = locationService.getLocationByIdDto(id);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getName()).isEqualTo("Get Shop");
        assertThat(result.orElseThrow().getCode()).isEqualTo("GET-001");
    }

    @Test
    void getLocation_notFound_throwsException() {
        UUID id = UUID.randomUUID();
        when(locationRepository.findById(id)).thenReturn(Optional.empty());
        Optional<Location> result = locationService.getLocationById(id);

        assertThatThrownBy(() -> throwNotFound(result))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException ex = (ResponseStatusException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    private static void throwNotFound(Optional<Location> location) {
        location.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Test
    void listLocations_defaultsToActive_returnsPage() {
        UUID id = UUID.randomUUID();
        var pageable = PageRequest.of(0, 10);
        LocationType locationType = LocationType.builder().id(UUID.randomUUID()).name("Shop").build();
        Location activeLocation = savedLocation(id, validRequest("Active Shop", "ACT-001"), locationType);
        Page<Location> page = new PageImpl<>(List.of(activeLocation), pageable, 1);

        when(locationRepository.findByStatus("ACTIVE", pageable)).thenReturn(page);

        Page<LocationResponseDTO> result = locationService.listLocations(" ", pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().getFirst().getName()).isEqualTo("Active Shop");
        verify(locationRepository).findByStatus("ACTIVE", pageable);
    }

    @Test
    void listLocations_withStatusFilter_usesNormalizedStatus() {
        UUID id = UUID.randomUUID();
        var pageable = PageRequest.of(0, 5);
        LocationType locationType = LocationType.builder().id(UUID.randomUUID()).name("Shop").build();
        Location inactiveLocation = savedLocation(id, validRequest("Inactive Shop", "INA-001"), locationType);
        Page<Location> page = new PageImpl<>(List.of(inactiveLocation), pageable, 1);

        when(locationRepository.findByStatus("INACTIVE", pageable)).thenReturn(page);

        Page<LocationResponseDTO> result = locationService.listLocations(" inactive ", pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(locationRepository).findByStatus("INACTIVE", pageable);
    }

    @Test
    void updateLocation_validPayload_returnsResponse() {
        UUID id = UUID.randomUUID();
        Location existing = savedLocation(id, validRequest("Legacy Name", "UPD-001"),
                LocationType.builder().id(UUID.randomUUID()).name("Shop").build());
        LocationRequestDTO replacement = validRequest("Updated Name", "UPD-001");
        Location saved = savedLocation(id, replacement,
                LocationType.builder().id(UUID.randomUUID()).name("Shop").build());

        when(locationRepository.findById(id)).thenReturn(Optional.of(existing));
        when(locationTypeRepository.findByNameIgnoreCase("Shop"))
                .thenReturn(Optional.of(LocationType.builder().id(UUID.randomUUID()).name("Shop").build()));
        when(locationRepository.saveAndFlush(any(Location.class))).thenReturn(saved);

        Optional<LocationResponseDTO> response = locationService.updateLocation(id, replacement);

        assertThat(response).isPresent();
        assertThat(response.orElseThrow().getName()).isEqualTo("Updated Name");
    }

    @Test
    void updateLocation_optimisticLockConflict_throwsException() {
        UUID id = UUID.randomUUID();
        Location existing = savedLocation(id, validRequest("Shop", "LOCK-001"),
                LocationType.builder().id(UUID.randomUUID()).name("Shop").build());
        LocationRequestDTO update = validRequest("Shop", "LOCK-001");

        when(locationRepository.findById(id)).thenReturn(Optional.of(existing));
        when(locationTypeRepository.findByNameIgnoreCase("Shop"))
                .thenReturn(Optional.of(LocationType.builder().id(UUID.randomUUID()).name("Shop").build()));
        when(locationRepository.saveAndFlush(any(Location.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Location.class, id));

        assertThatThrownBy(() -> locationService.updateLocation(id, update))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException ex = (ResponseStatusException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).contains("OPTIMISTIC_LOCK_FAILED");
                });
    }

    @Test
    void patchLocation_deactivate_setsStatusInactive() {
        UUID id = UUID.randomUUID();
        Location existing = savedLocation(id, validRequest("Patch Shop", "PAT-001"),
                LocationType.builder().id(UUID.randomUUID()).name("Shop").build());
        existing.setStatus("ACTIVE");
        existing.setActive(true);
        LocationPatchRequest patch = LocationPatchRequest.builder().status("INACTIVE").build();

        when(locationRepository.findById(id)).thenReturn(Optional.of(existing));
        when(locationRepository.saveAndFlush(any(Location.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LocationResponseDTO response = locationService.patchLocation(id, patch);

        assertThat(response.isActive()).isFalse();
        assertThat(existing.getStatus()).isEqualTo("INACTIVE");
        assertThat(existing.isActive()).isFalse();
    }

    @Test
    void patchLocation_duplicateName_throwsConflict() {
        UUID id = UUID.randomUUID();
        Location existing = savedLocation(id, validRequest("Patch Name", "PAT-002"),
                LocationType.builder().id(UUID.randomUUID()).name("Shop").build());
        LocationPatchRequest patch = LocationPatchRequest.builder().name("Duplicate Name").build();

        when(locationRepository.findById(id)).thenReturn(Optional.of(existing));
        when(locationRepository.findByNormalizedNameAndIdNot("duplicate name", id))
                .thenReturn(Optional.of(savedLocation(UUID.randomUUID(), validRequest("Duplicate Name", "DUP-001"),
                        LocationType.builder().id(UUID.randomUUID()).name("Shop").build())));

        assertThatThrownBy(() -> locationService.patchLocation(id, patch))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException ex = (ResponseStatusException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).contains("LOCATION_NAME_TAKEN");
                });
    }

    @Test
    void patchLocation_nameChange_updatesNameAndPersists() {
        UUID id = UUID.randomUUID();
        Location existing = savedLocation(id, validRequest("Old Name", "PAT-003"),
                LocationType.builder().id(UUID.randomUUID()).name("Shop").build());
        LocationPatchRequest patch = LocationPatchRequest.builder().name("Renamed Location").build();

        when(locationRepository.findById(id)).thenReturn(Optional.of(existing));
        when(locationRepository.findByNormalizedNameAndIdNot("renamed location", id)).thenReturn(Optional.empty());
        when(locationRepository.saveAndFlush(any(Location.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LocationResponseDTO response = locationService.patchLocation(id, patch);

        assertThat(response.getName()).isEqualTo("Renamed Location");
        assertThat(existing.getName()).isEqualTo("Renamed Location");
    }

    @Test
    void patchLocation_serializesScheduleFieldsAsCanonicalJson() {
        UUID id = UUID.randomUUID();
        Location existing = savedLocation(id, validRequest("Patch Json", "PAT-JSON"),
                LocationType.builder().id(UUID.randomUUID()).name("Shop").build());
        LocationPatchRequest patch = LocationPatchRequest.builder()
                .operatingHours(List.of(
                        OperatingHoursRequest.builder().dayOfWeek("WEDNESDAY").openTime(LocalTime.of(10, 0))
                                .closeTime(LocalTime.of(19, 0)).build(),
                        OperatingHoursRequest.builder().dayOfWeek("MONDAY").openTime(LocalTime.of(8, 0))
                                .closeTime(LocalTime.of(17, 0)).build()))
                .holidayClosures(List.of(
                        HolidayClosureRequest.builder().date(LocalDate.of(2026, 11, 26)).reason("Thanksgiving")
                                .build(),
                        HolidayClosureRequest.builder().date(LocalDate.of(2026, 1, 1)).reason("New Year").build()))
                .build();

        when(locationRepository.findById(id)).thenReturn(Optional.of(existing));
        when(locationRepository.saveAndFlush(any(Location.class))).thenAnswer(invocation -> invocation.getArgument(0));

        locationService.patchLocation(id, patch);

        assertThat(existing.getOperatingHours()).isEqualTo(
                "[{\"dayOfWeek\":\"MONDAY\",\"openTime\":\"08:00:00\",\"closeTime\":\"17:00:00\"},"
                        + "{\"dayOfWeek\":\"WEDNESDAY\",\"openTime\":\"10:00:00\",\"closeTime\":\"19:00:00\"}]");
        assertThat(existing.getHolidayClosures()).isEqualTo(
                "[{\"date\":\"2026-01-01\",\"reason\":\"New Year\"},"
                        + "{\"date\":\"2026-11-26\",\"reason\":\"Thanksgiving\"}]");
    }

    @Test
    void deleteLocation_existingId_delegatesToRepository() {
        UUID id = UUID.randomUUID();

        locationService.deleteLocation(id);

        verify(locationRepository).deleteById(id);
    }

    @Test
    void getLocationValidation_existingLocation_marksExistsAndActive() {
        UUID id = UUID.randomUUID();
        Location location = Location.builder().id(id).active(true).build();
        when(locationRepository.findById(id)).thenReturn(Optional.of(location));

        var result = locationService.getLocationValidation(id);

        assertThat(result.isExists()).isTrue();
        assertThat(result.isActive()).isTrue();
    }

    @Test
    void getLocationValidation_missingLocation_marksNotFound() {
        UUID id = UUID.randomUUID();
        when(locationRepository.findById(id)).thenReturn(Optional.empty());

        var result = locationService.getLocationValidation(id);

        assertThat(result.isExists()).isFalse();
        assertThat(result.isActive()).isFalse();
    }

    @Test
    void getAllLocationsDto_mapsRepositoryEntitiesToDtos() {
        LocationType locationType = LocationType.builder().id(UUID.randomUUID()).name("Shop").build();
        Location location = savedLocation(UUID.randomUUID(), validRequest("List Shop", "LST-001"), locationType);
        when(locationRepository.findAll()).thenReturn(List.of(location));

        List<LocationResponseDTO> results = locationService.getAllLocationsDto();

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getName()).isEqualTo("List Shop");
        assertThat(results.getFirst().getType()).isNotNull();
    }

    @Test
    void addParent_selfParent_throwsIllegalArgumentException() {
        UUID same = UUID.randomUUID();

        assertThatThrownBy(() -> locationService.addParent(same, same, ParentType.HOME_OFFICE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be its own parent");
    }

    @Test
    void addParent_validRelationship_returnsResponseDto() {
        UUID childId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID parentId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Location child = Location.builder().id(childId).name("Child").active(true).build();
        Location parent = Location.builder().id(parentId).name("Parent").active(true).build();
        LocationParent savedRelationship = LocationParent.builder()
                .id(UUID.randomUUID())
                .child(child)
                .parent(parent)
                .parentType(ParentType.HOME_OFFICE)
                .build();

        when(locationRepository.findByIdForUpdate(childId)).thenReturn(Optional.of(child));
        when(locationRepository.findByIdForUpdate(parentId)).thenReturn(Optional.of(parent));
        when(locationParentRepository.existsByChild_IdAndParentType(childId, ParentType.HOME_OFFICE)).thenReturn(false);
        when(locationParentRepository.existsByChild_IdAndParent_Id(childId, parentId)).thenReturn(false);
        when(locationParentRepository.existsByChild_IdAndParent_Id(parentId, childId)).thenReturn(false);
        when(locationParentRepository.isDescendant(childId, parentId)).thenReturn(false);
        when(locationParentRepository.saveAndFlush(any(LocationParent.class))).thenReturn(savedRelationship);

        var result = locationService.addParent(childId, parentId, "HOME_OFFICE");

        assertThat(result.getParentId()).isEqualTo(parentId);
        assertThat(result.getChildId()).isEqualTo(childId);
        assertThat(result.getParentType()).isEqualTo("HOME_OFFICE");
    }

    @Test
    void getAllChildrenDto_withParentType_filtersByType() {
        UUID parentId = UUID.randomUUID();
        Location child = Location.builder().id(UUID.randomUUID()).name("Typed Child").active(true).build();
        Location parent = Location.builder().id(parentId).name("HQ").active(true).build();
        LocationParent relationship = LocationParent.builder()
                .id(UUID.randomUUID())
                .parent(parent)
                .child(child)
                .parentType(ParentType.HOME_OFFICE)
                .build();

        when(locationParentRepository.findByParent_IdAndParentType(parentId, ParentType.HOME_OFFICE))
                .thenReturn(List.of(relationship));

        List<LocationResponseDTO> results = locationService.getAllChildrenDto(parentId, "home_office");

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getId()).isEqualTo(child.getId());
    }

    @Test
    void getResponsiblePerson_withId_callsPersonClient() {
        UUID locationId = UUID.randomUUID();
        Location location = Location.builder().id(locationId).responsiblePersonId(99L).active(true).build();
        PersonDTO person = new PersonDTO();
        person.setId(99L);
        person.setFirstName("Alex");
        person.setLastName("Tech");

        when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));
        when(personClient.getPersonById(99L)).thenReturn(person);

        PersonDTO result = locationService.getResponsiblePerson(locationId);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(99L);
    }

    private static LocationRequestDTO validRequest(String name, String code) {
        return LocationRequestDTO.builder()
                .name(name)
                .code(code)
                .type(LocationTypeDTO.builder().name("Shop").description("Shop type").build())
                .build();
    }

    private static Location savedLocation(UUID id, LocationRequestDTO request, LocationType locationType) {
        return Location.builder()
                .id(id)
                .name(request.getName())
                .code(request.getCode())
                .active(true)
                .type(locationType)
                .build();
    }

}
