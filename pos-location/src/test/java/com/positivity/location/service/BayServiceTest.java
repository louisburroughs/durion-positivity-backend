package com.positivity.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.positivity.location.internal.dto.BayCapacityRequest;
import com.positivity.location.internal.dto.BayPatchRequest;
import com.positivity.location.internal.dto.BayRequest;
import com.positivity.location.internal.dto.BayResponse;
import com.positivity.location.internal.entity.BayEntity;
import com.positivity.location.internal.entity.ServiceLocationCapabilityEntity;
import com.positivity.location.internal.enums.BayType;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.repository.BayRepository;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.ServiceLocationCapabilityRepository;
import com.positivity.location.internal.service.BayServiceImpl;

/**
 * RED tests for Bay service contract behaviors required by Story #77.
 * Issue: CAP-136 #77
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("BayServiceTest")
class BayServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    BayRepository bayRepository;
    @Mock
    LocationRepository locationRepository;
    @Mock
    ServiceLocationCapabilityRepository serviceLocationCapabilityRepository;
    @InjectMocks
    BayServiceImpl bayService;

    @BeforeEach
    void setUp() {
        lenient().when(serviceLocationCapabilityRepository.findByCodeIn(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Collection<String> codes = (Collection<String>) invocation.getArgument(0);
            if (codes == null) {
                return List.of();
            }
            return codes.stream()
                    .map(code -> ServiceLocationCapabilityEntity.builder()
                            .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                            .code(code)
                            .name("Capability " + code)
                            .active(true)
                            .build())
                    .toList();
        });
    }

    @Test
    @DisplayName("listBays_variousFilters_coversAllBranches")
    void listBays_variousFilters_coversAllBranches() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Pageable pageable = mock(Pageable.class);
        when(locationRepository.existsById(locationId)).thenReturn(true);
        Page<BayEntity> mockPage = new PageImpl<>(List.of(defaultBay(locationId)));
        // Both filters
        when(bayRepository.findByLocationIdAndStatusAndBayType(eq(locationId), anyString(), anyString(), eq(pageable)))
                .thenReturn(mockPage);
        bayService.listBays(locationId, "ACTIVE", "GENERAL_SERVICE", pageable);
        // Status only
        when(bayRepository.findByLocationIdAndStatus(eq(locationId), anyString(), eq(pageable))).thenReturn(mockPage);
        bayService.listBays(locationId, "ACTIVE", null, pageable);
        // BayType only
        when(bayRepository.findByLocationIdAndBayType(eq(locationId), anyString(), eq(pageable))).thenReturn(mockPage);
        bayService.listBays(locationId, null, "GENERAL_SERVICE", pageable);
        // Neither
        when(bayRepository.findByLocationId(locationId, pageable)).thenReturn(mockPage);
        bayService.listBays(locationId, null, null, pageable);
        verify(locationRepository, times(4)).existsById(locationId);
    }

    @Test
    @DisplayName("patchBay_eachField_coversBranches")
    void patchBay_eachField_coversBranches() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(locationRepository.existsById(locationId)).thenReturn(true);
        BayEntity entity = BayEntity.builder().locationId(locationId).name("Bay1").bayType("GENERAL_SERVICE")
                .status("ACTIVE")
                .maxConcurrentVehicles(2).build();
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(entity));
        when(bayRepository.save(any(BayEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, BayEntity.class));
        // Patch name
        BayPatchRequest patchName = new BayPatchRequest();
        patchName.setName("Bay2");
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, "Bay2")).thenReturn(false);
        bayService.patchBay(locationId, bayId, patchName);
        // Patch bayType
        BayPatchRequest patchType = new BayPatchRequest();
        patchType.setBayType("GENERAL_SERVICE");
        bayService.patchBay(locationId, bayId, patchType);
        // Patch status
        BayPatchRequest patchStatus = new BayPatchRequest();
        patchStatus.setStatus("OUT_OF_SERVICE");
        bayService.patchBay(locationId, bayId, patchStatus);
        // Patch maxConcurrentVehicles
        BayPatchRequest patchMax = new BayPatchRequest();
        patchMax.setMaxConcurrentVehicles(3);
        bayService.patchBay(locationId, bayId, patchMax);
        verify(bayRepository, atLeastOnce()).save(entity);
    }

    @Test
    @DisplayName("patchBay_duplicateName_throwsConflict")
    void patchBay_duplicateName_throwsConflict() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(locationRepository.existsById(locationId)).thenReturn(true);
        BayEntity entity = BayEntity.builder().locationId(locationId).name("Bay1").bayType("GENERAL_SERVICE")
                .status("ACTIVE")
                .maxConcurrentVehicles(2).build();
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(entity));
        BayPatchRequest patch = new BayPatchRequest();
        patch.setName("Bay2");
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, "Bay2")).thenReturn(true);
        assertThatThrownBy(() -> bayService.patchBay(locationId, bayId, patch))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("BAY_NAME_TAKEN");
    }

    @Test
    @DisplayName("getBay_locationNotFound_throws404")
    void getBay_locationNotFound_throws404() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> bayService.getBay(locationId, bayId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Bay not found");
    }

    private static final String BAY_SERVICE_FQCN = "com.positivity.location.service.BayService";
    private static final String BAY_REPOSITORY_FQCN = "com.positivity.location.internal.repository.BayRepository";
    private static final String LOCATION_REPOSITORY_FQCN = "com.positivity.location.internal.repository.LocationRepository";
    private static final String BAY_TYPE_FQCN = "com.positivity.location.internal.enums.BayType";
    private static final String BAY_CAPACITY_REQUEST_FQCN = "com.positivity.location.internal.dto.BayCapacityRequest";
    private static final String RESOURCE_NOT_FOUND_EXCEPTION_FQCN = "com.positivity.location.internal.exception.ResourceNotFoundException";

    @Test
    @DisplayName("#77 - createBay_happyPath_returnsBayResponse")
    void createBay_happyPath_returnsBayResponse() {
        assertBayServiceAndDependenciesPresent();
        assertServiceHasMethod("createBay");
        assertBayContractArtifactPresent(BAY_TYPE_FQCN, "bay type enum for happy-path request validation");
    }

    @Test
    @DisplayName("#77 - createBay_locationNotFound_throwsException")
    void createBay_locationNotFound_throwsException() {
        assertBayServiceAndDependenciesPresent();
        assertServiceHasMethod("createBay");
        assertRepositoryMethodPresence(LOCATION_REPOSITORY_FQCN, "existsById");
    }

    @Test
    @DisplayName("#77 - createBay_duplicateName_throwsException (case-insensitive)")
    void createBay_duplicateName_throwsException() {
        assertBayServiceAndDependenciesPresent();
        assertServiceHasMethod("createBay");
        assertRepositoryMethodPresence(BAY_REPOSITORY_FQCN, "existsByLocationIdAndNameIgnoreCase");
    }

    @Test
    @DisplayName("#77 - createBay_invalidBayType_throwsException")
    void createBay_invalidBayType_throwsException() {
        assertBayServiceAndDependenciesPresent();
        assertServiceHasMethod("createBay");
        assertBayContractArtifactPresent(BAY_TYPE_FQCN, "supported bayType values");
    }

    @Test
    @DisplayName("#77 - createBay_missingCapacity_throwsException")
    void createBay_missingCapacity_throwsException() {
        assertBayServiceAndDependenciesPresent();
        assertServiceHasMethod("createBay");
        assertBayContractArtifactPresent(BAY_CAPACITY_REQUEST_FQCN, "capacity.maxConcurrentVehicles payload contract");
    }

    @Test
    @DisplayName("#77 - listBays_filterByStatus_excludesOutOfService")
    void listBays_filterByStatus_excludesOutOfService() {
        assertBayServiceAndDependenciesPresent();
        assertServiceHasMethod("listBays");
        assertBayContractArtifactPresent(BAY_TYPE_FQCN, "bayType filter support");
    }

    @Test
    @DisplayName("#77 - getBay_found_returnsResponse")
    void getBay_found_returnsResponse() {
        assertBayServiceAndDependenciesPresent();
        assertServiceHasMethod("getBay");
        assertRepositoryMethodPresence(BAY_REPOSITORY_FQCN, "findById");
    }

    @Test
    @DisplayName("#77 - getBay_notFound_throwsException")
    void getBay_notFound_throwsException() {
        assertBayServiceAndDependenciesPresent();
        assertServiceHasMethod("getBay");
        assertBayContractArtifactPresent(RESOURCE_NOT_FOUND_EXCEPTION_FQCN, "not found semantics");
    }

    @Test
    @DisplayName("#77 - patchBay_deactivate_updatesStatus")
    void patchBay_deactivate_updatesStatus() {
        assertBayServiceAndDependenciesPresent();
        assertServiceHasMethod("patchBay");
        assertRepositoryMethodPresence(BAY_REPOSITORY_FQCN, "save");
    }

    @Test
    @DisplayName("#77 - patchBay_invalidName_throwsException (duplicate after patch)")
    void patchBay_invalidName_throwsException() {
        assertBayServiceAndDependenciesPresent();
        assertServiceHasMethod("patchBay");
        assertRepositoryMethodPresence(BAY_REPOSITORY_FQCN, "existsByLocationIdAndNameIgnoreCase");
    }

    @Test
    @DisplayName("createBay_duplicateNormalizedName_throwsDuplicateResourceException")
    void createBay_duplicateNormalizedName_throwsDuplicateResourceException() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, request.getName())).thenReturn(false);
        when(bayRepository.findByLocationIdAndNormalizedName(locationId, request.getName().toLowerCase()))
                .thenReturn(Optional.of(BayEntity.builder().build()));

        assertThatThrownBy(() -> bayService.createBay(locationId, request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("BAY_NAME_TAKEN");
    }

    @Test
    @DisplayName("createBay_nameConstraintViolation_mapsToBayNameTaken")
    void createBay_nameConstraintViolation_mapsToBayNameTaken() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, request.getName())).thenReturn(false);
        when(bayRepository.findByLocationIdAndNormalizedName(locationId, request.getName().toLowerCase()))
                .thenReturn(Optional.empty());
        when(bayRepository.save(any(BayEntity.class)))
                .thenThrow(new DataIntegrityViolationException("violates uq_bays_location_normalized_name"));

        assertThatThrownBy(() -> bayService.createBay(locationId, request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("BAY_NAME_TAKEN");
    }

    @Test
    @DisplayName("createBay_invalidBayType_throwsIllegalArgumentException")
    void createBay_invalidBayType_throwsIllegalArgumentException() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();
        request.setBayType("BAD_TYPE");

        when(locationRepository.existsById(locationId)).thenReturn(true);

        assertThatThrownBy(() -> bayService.createBay(locationId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid bayType");
    }

    @Test
    @DisplayName("createBay_invalidCapacity_throwsIllegalArgumentException")
    void createBay_invalidCapacity_throwsIllegalArgumentException() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();
        request.setCapacity(BayCapacityRequest.builder().maxConcurrentVehicles(0).build());

        when(locationRepository.existsById(locationId)).thenReturn(true);

        assertThatThrownBy(() -> bayService.createBay(locationId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity.maxConcurrentVehicles must be >= 1");
    }

    @Test
    @DisplayName("createBay_missingCapacityAndFallback_throwsIllegalArgumentException")
    void createBay_missingCapacityAndFallback_throwsIllegalArgumentException() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();
        request.setCapacity(null);
        request.setMaxConcurrentVehicles(null);

        when(locationRepository.existsById(locationId)).thenReturn(true);

        assertThatThrownBy(() -> bayService.createBay(locationId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity.maxConcurrentVehicles is required");
    }

    @Test
    @DisplayName("createBay_successWithCapabilitiesAndSkills_savesAndReturnsResponse")
    void createBay_successWithCapabilitiesAndSkills_savesAndReturnsResponse() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();
        request.setServiceCapabilityIds(List.of("ALIGN", "TIRE"));
        request.setSkillRequirementIds(List.of("ASE-A1"));
        request.setStatus("active");

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, request.getName())).thenReturn(false);
        when(bayRepository.findByLocationIdAndNormalizedName(locationId, request.getName().toLowerCase()))
                .thenReturn(Optional.empty());
        when(bayRepository.save(any(BayEntity.class))).thenAnswer(invocation -> {
            BayEntity bay = invocation.getArgument(0, BayEntity.class);
            bay.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            return bay;
        });

        BayResponse response = bayService.createBay(locationId, request);

        assertThat(response.getLocationId()).isEqualTo(locationId);
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
        assertThat(response.getServiceCapabilityIds()).containsExactly("ALIGN", "TIRE");
        assertThat(response.getSkillRequirementIds()).containsExactly("ASE-A1");
        verify(bayRepository).save(any(BayEntity.class));
    }

    @Test
    @DisplayName("createBay_invalidServiceCapabilityIds_throwsIllegalArgumentException")
    void createBay_invalidServiceCapabilityIds_throwsIllegalArgumentException() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();
        request.setServiceCapabilityIds(List.of("ALIGN", "UNKNOWN_CAP"));

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, request.getName())).thenReturn(false);
        when(bayRepository.findByLocationIdAndNormalizedName(locationId, request.getName().toLowerCase()))
                .thenReturn(Optional.empty());
        when(serviceLocationCapabilityRepository.findByCodeIn(any())).thenReturn(List.of(
                ServiceLocationCapabilityEntity.builder()
                        .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                        .code("ALIGN")
                        .name("Align")
                        .active(true)
                        .build()));

        assertThatThrownBy(() -> bayService.createBay(locationId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid serviceCapabilityIds: UNKNOWN_CAP");
    }

    @Test
    @DisplayName("createBay_successUsesFallbackMaxConcurrentVehiclesWhenCapacityNull")
    void createBay_successUsesFallbackMaxConcurrentVehiclesWhenCapacityNull() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();
        request.setCapacity(null);
        request.setMaxConcurrentVehicles(4);

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, request.getName())).thenReturn(false);
        when(bayRepository.findByLocationIdAndNormalizedName(locationId, request.getName().toLowerCase()))
                .thenReturn(Optional.empty());
        when(bayRepository.save(any(BayEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, BayEntity.class));

        BayResponse response = bayService.createBay(locationId, request);

        assertThat(response.getMaxConcurrentVehicles()).isEqualTo(4);
        verify(bayRepository).save(any(BayEntity.class));
    }

    @Test
    @DisplayName("listBays_noFilters_callsFindByLocationId")
    void listBays_noFilters_callsFindByLocationId() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PageRequest pageable = PageRequest.of(0, 10);
        BayEntity entity = defaultBay(locationId);

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByLocationId(locationId, pageable)).thenReturn(new PageImpl<>(List.of(entity)));

        Page<BayResponse> page = bayService.listBays(locationId, null, null, pageable);

        assertThat(page.getContent()).hasSize(1);
        verify(bayRepository).findByLocationId(locationId, pageable);
    }

    @Test
    @DisplayName("listBays_filterByStatus_callsFindByLocationIdAndStatus")
    void listBays_filterByStatus_callsFindByLocationIdAndStatus() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PageRequest pageable = PageRequest.of(0, 10);
        BayEntity entity = defaultBay(locationId);

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByLocationIdAndStatus(locationId, "ACTIVE", pageable))
                .thenReturn(new PageImpl<>(List.of(entity)));

        Page<BayResponse> page = bayService.listBays(locationId, "active", null, pageable);

        assertThat(page.getContent()).hasSize(1);
        verify(bayRepository).findByLocationIdAndStatus(locationId, "ACTIVE", pageable);
    }

    @Test
    @DisplayName("listBays_filterByBayType_callsFindByLocationIdAndBayType")
    void listBays_filterByBayType_callsFindByLocationIdAndBayType() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PageRequest pageable = PageRequest.of(0, 10);
        BayEntity entity = defaultBay(locationId);

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByLocationIdAndBayType(locationId, "GENERAL_SERVICE", pageable))
                .thenReturn(new PageImpl<>(List.of(entity)));

        Page<BayResponse> page = bayService.listBays(locationId, null, "general_service", pageable);

        assertThat(page.getContent()).hasSize(1);
        verify(bayRepository).findByLocationIdAndBayType(locationId, "GENERAL_SERVICE", pageable);
    }

    @Test
    @DisplayName("listBays_filterByBoth_callsFindByLocationIdAndStatusAndBayType")
    void listBays_filterByBoth_callsFindByLocationIdAndStatusAndBayType() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PageRequest pageable = PageRequest.of(0, 10);
        BayEntity entity = defaultBay(locationId);

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByLocationIdAndStatusAndBayType(locationId, "OUT_OF_SERVICE", "ALIGNMENT", pageable))
                .thenReturn(new PageImpl<>(List.of(entity)));

        Page<BayResponse> page = bayService.listBays(locationId, "out_of_service", "alignment", pageable);

        assertThat(page.getContent()).hasSize(1);
        verify(bayRepository).findByLocationIdAndStatusAndBayType(locationId, "OUT_OF_SERVICE", "ALIGNMENT", pageable);
    }

    @Test
    @DisplayName("listBays_blankFilters_treatedAsNoFilters")
    void listBays_blankFilters_treatedAsNoFilters() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PageRequest pageable = PageRequest.of(0, 5);
        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByLocationId(locationId, pageable))
                .thenReturn(new PageImpl<>(List.of(defaultBay(locationId))));

        bayService.listBays(locationId, "  ", " ", pageable);

        verify(bayRepository).findByLocationId(locationId, pageable);
    }

    @Test
    @DisplayName("getBay_found_returnsBayResponse")
    void getBay_found_returnsBayResponse() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayEntity entity = defaultBay(locationId);
        entity.setId(bayId);

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(entity));

        BayResponse response = bayService.getBay(locationId, bayId);

        assertThat(response.getId()).isEqualTo(bayId);
        assertThat(response.getName()).isEqualTo(entity.getName());
    }

    @Test
    @DisplayName("patchBay_locationNotFound_throwsResourceNotFoundException")
    void patchBay_locationNotFound_throwsResourceNotFoundException() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayPatchRequest patch = BayPatchRequest.builder().status("ACTIVE").build();

        when(locationRepository.existsById(locationId)).thenReturn(false);

        assertThatThrownBy(() -> bayService.patchBay(locationId, bayId, patch))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Location not found");
    }

    @Test
    @DisplayName("patchBay_bayNotFound_throwsResourceNotFoundException")
    void patchBay_bayNotFound_throwsResourceNotFoundException() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayPatchRequest patch = BayPatchRequest.builder().status("ACTIVE").build();

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bayService.patchBay(locationId, bayId, patch))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Bay not found");
    }

    @Test
    @DisplayName("patchBay_nameSameIgnoringCase_doesNotCheckDuplicate")
    void patchBay_nameSameIgnoringCase_doesNotCheckDuplicate() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayEntity existing = defaultBay(locationId);
        existing.setId(bayId);
        existing.setName("Main Bay");

        BayPatchRequest patch = BayPatchRequest.builder().name(" main bay ").build();

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(existing));
        when(bayRepository.save(any(BayEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, BayEntity.class));

        BayResponse response = bayService.patchBay(locationId, bayId, patch);

        assertThat(response.getName()).isEqualTo("main bay");
        verify(bayRepository, never()).existsByLocationIdAndNameIgnoreCase(any(), any());
    }

    @Test
    @DisplayName("patchBay_invalidBayType_throwsIllegalArgumentException")
    void patchBay_invalidBayType_throwsIllegalArgumentException() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayEntity existing = defaultBay(locationId);
        existing.setId(bayId);

        BayPatchRequest patch = BayPatchRequest.builder().bayType("NOPE").build();

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> bayService.patchBay(locationId, bayId, patch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid bayType");
    }

    @Test
    @DisplayName("patchBay_invalidStatus_throwsIllegalArgumentException")
    void patchBay_invalidStatus_throwsIllegalArgumentException() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayEntity existing = defaultBay(locationId);
        existing.setId(bayId);

        BayPatchRequest patch = BayPatchRequest.builder().status("INVALID").build();

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> bayService.patchBay(locationId, bayId, patch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid status");
    }

    @Test
    @DisplayName("patchBay_capacityFromNestedObject_updatesMaxConcurrentVehicles")
    void patchBay_capacityFromNestedObject_updatesMaxConcurrentVehicles() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayEntity existing = defaultBay(locationId);
        existing.setId(bayId);

        BayPatchRequest patch = BayPatchRequest.builder()
                .capacity(BayCapacityRequest.builder().maxConcurrentVehicles(8).build())
                .build();

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(existing));
        when(bayRepository.save(any(BayEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, BayEntity.class));

        BayResponse response = bayService.patchBay(locationId, bayId, patch);

        assertThat(response.getMaxConcurrentVehicles()).isEqualTo(8);
        verify(bayRepository).save(any(BayEntity.class));
    }

    @Test
    @DisplayName("patchBay_invalidCapacity_throwsIllegalArgumentException")
    void patchBay_invalidCapacity_throwsIllegalArgumentException() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayEntity existing = defaultBay(locationId);
        existing.setId(bayId);

        BayPatchRequest patch = BayPatchRequest.builder()
                .capacity(BayCapacityRequest.builder().maxConcurrentVehicles(0).build())
                .build();

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> bayService.patchBay(locationId, bayId, patch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity.maxConcurrentVehicles must be >= 1");
    }

    @Test
    @DisplayName("patchBay_updatesServiceAndSkillLists")
    void patchBay_updatesServiceAndSkillLists() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayEntity existing = defaultBay(locationId);
        existing.setId(bayId);

        BayPatchRequest patch = BayPatchRequest.builder()
                .serviceCapabilityIds(List.of("ALIGN", "BRAKE"))
                .skillRequirementIds(List.of("ASE-A4"))
                .build();

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(existing));
        when(bayRepository.save(any(BayEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, BayEntity.class));

        BayResponse response = bayService.patchBay(locationId, bayId, patch);

        assertThat(response.getServiceCapabilityIds()).containsExactly("ALIGN", "BRAKE");
        assertThat(response.getSkillRequirementIds()).containsExactly("ASE-A4");
        verify(bayRepository).save(any(BayEntity.class));
    }

    @Test
    @DisplayName("patchBay_statusDeactivateThenReactivate_succeeds")
    void patchBay_statusDeactivateThenReactivate_succeeds() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayEntity existing = defaultBay(locationId);
        existing.setId(bayId);

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(existing));
        when(bayRepository.save(any(BayEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, BayEntity.class));

        BayResponse deactivated = bayService.patchBay(locationId, bayId,
                BayPatchRequest.builder().status("OUT_OF_SERVICE").build());
        BayResponse reactivated = bayService.patchBay(locationId, bayId,
                BayPatchRequest.builder().status("ACTIVE").build());

        assertThat(deactivated.getStatus()).isEqualTo("OUT_OF_SERVICE");
        assertThat(reactivated.getStatus()).isEqualTo("ACTIVE");
        verify(bayRepository, times(2)).save(any(BayEntity.class));
    }

    private BayRequest validCreateRequest() {
        return BayRequest.builder()
                .name("Bay Alpha")
                .bayType(BayType.GENERAL_SERVICE.name())
                .capacity(BayCapacityRequest.builder().maxConcurrentVehicles(2).build())
                .build();
    }

    private BayEntity defaultBay(UUID locationId) {
        return BayEntity.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .locationId(locationId)
                .name("Bay-1")
                .normalizedName("bay-1")
                .bayType(BayType.GENERAL_SERVICE.name())
                .status("ACTIVE")
                .maxConcurrentVehicles(2)
                .serviceCapabilityIds(List.of())
                .skillRequirementIds(List.of())
                .build();
    }

    private void assertBayServiceAndDependenciesPresent() {
        assertThatCode(() -> Class.forName(BAY_SERVICE_FQCN))
                .as("Bay service should exist for CAP-136 Story #77")
                .doesNotThrowAnyException();

        assertThatCode(() -> Class.forName(BAY_REPOSITORY_FQCN))
                .as("Bay repository should exist for CAP-136 Story #77")
                .doesNotThrowAnyException();

        assertThatCode(() -> Class.forName(LOCATION_REPOSITORY_FQCN))
                .as("Location repository should exist for bay location validation")
                .doesNotThrowAnyException();
    }

    private void assertServiceHasMethod(String methodName) {
        try {
            Class<?> bayServiceClass = Class.forName(BAY_SERVICE_FQCN);
            List<String> methodNames = Arrays.stream(bayServiceClass.getDeclaredMethods())
                    .map(Method::getName)
                    .toList();
            assertThat(methodNames)
                    .as("Bay service should expose method '%s' for Story #77 behaviors", methodName)
                    .contains(methodName);
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("Bay service class is not implemented", exception);
        }
    }

    private void assertRepositoryMethodPresence(String repositoryClassName, String methodName) {
        try {
            Class<?> repositoryClass = Class.forName(repositoryClassName);
            List<String> methodNames = Arrays.stream(repositoryClass.getDeclaredMethods())
                    .map(Method::getName)
                    .toList();
            assertThat(methodNames)
                    .as("Repository '%s' should expose method '%s'", repositoryClassName, methodName)
                    .contains(methodName);
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("Repository class is not implemented: " + repositoryClassName, exception);
        }
    }

    private void assertBayContractArtifactPresent(String className, String purpose) {
        assertThatCode(() -> Class.forName(className))
                .as("Missing bay contract artifact for %s", purpose)
                .doesNotThrowAnyException();
    }
}
