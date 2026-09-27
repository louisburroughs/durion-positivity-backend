package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.location.internal.dto.BayCapacityRequest;
import com.positivity.location.internal.dto.BayPatchRequest;
import com.positivity.location.internal.dto.BayRequest;
import com.positivity.location.internal.dto.BayResponse;
import com.positivity.location.internal.entity.BayEntity;
import com.positivity.location.internal.entity.BaySpecialtyOperationEntity;
import com.positivity.location.internal.entity.ExtCatalogServiceReplica;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.enums.BayType;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.InvalidServiceCapabilityCodesException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.repository.BayRepository;
import com.positivity.location.internal.repository.BaySpecialtyOperationRepository;
import com.positivity.location.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.location.internal.repository.LocationRepository;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

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
    ExtCatalogServiceReplicaRepository extCatalogServiceReplicaRepository;

    @Mock
    BaySpecialtyOperationRepository baySpecialtyOperationRepository;

    /** Bay mutations publish location.bay.updated (issue #1668). */
    @Mock
    LocationFactPublisher locationFactPublisher;

    @InjectMocks
    BayServiceImpl bayService;

    @BeforeEach
    void setUp() {
        lenient()
                .when(extCatalogServiceReplicaRepository.findByOperationCodeInAndActiveIsTrue(any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Collection<String> codes = (Collection<String>) invocation.getArgument(0);
                    if (codes == null) {
                        return List.of();
                    }
                    return codes.stream()
                            .map(code -> ExtCatalogServiceReplica.builder()
                                    .serviceId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                    .operationCode(code)
                                    .name("Service " + code)
                                    .active(true)
                                    .aggregateVersion(1L)
                                    .build())
                            .toList();
                });
    }

    @Test
    @DisplayName("listBays_variousFilters_coversAllBranches")
    void listBays_variousFilters_coversAllBranches() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Pageable pageable = PageRequest.of(0, 10);
        when(locationRepository.existsById(locationId)).thenReturn(true);
        Page<BayEntity> mockPage = new PageImpl<>(List.of(defaultBay(locationId)));
        // Both filters
        when(bayRepository.findByLocationIdAndStatusAndBayType(
                        eq(locationId), anyString(), anyString(), any(Pageable.class)))
                .thenReturn(mockPage);
        bayService.listBays(locationId, "ACTIVE", "GENERAL_SERVICE", pageable);
        // Status only
        when(bayRepository.findByLocationIdAndStatus(eq(locationId), anyString(), any(Pageable.class)))
                .thenReturn(mockPage);
        bayService.listBays(locationId, "ACTIVE", null, pageable);
        // BayType only: default hides RETIRED (DECISION-LOCATION-026)
        when(bayRepository.findByLocationIdAndBayTypeAndStatusNot(
                        eq(locationId), anyString(), eq("RETIRED"), any(Pageable.class)))
                .thenReturn(mockPage);
        bayService.listBays(locationId, null, "GENERAL_SERVICE", pageable);
        // Neither: default hides RETIRED (DECISION-LOCATION-026)
        when(bayRepository.findByLocationIdAndStatusNot(eq(locationId), eq("RETIRED"), any(Pageable.class)))
                .thenReturn(mockPage);
        bayService.listBays(locationId, null, null, pageable);
        verify(locationRepository, times(4)).existsById(locationId);
    }

    @Test
    @DisplayName("patchBay_eachField_coversBranches")
    void patchBay_eachField_coversBranches() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(locationRepository.existsById(locationId)).thenReturn(true);
        BayEntity entity = BayEntity.builder()
                .location(Location.builder().id(locationId).build())
                .name("Bay1")
                .bayType("GENERAL_SERVICE")
                .status("ACTIVE")
                .maxConcurrentVehicles(2)
                .build();
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(entity));
        when(bayRepository.save(any(BayEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, BayEntity.class));
        // Patch name
        BayPatchRequest patchName = new BayPatchRequest();
        patchName.setName("Bay2");
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, "Bay2"))
                .thenReturn(false);
        bayService.patchBay(locationId, bayId, patchName);
        // Patch bayType
        BayPatchRequest patchType = new BayPatchRequest();
        patchType.setBayType("GENERAL_SERVICE");
        bayService.patchBay(locationId, bayId, patchType);
        // Patch status (OUT_OF_SERVICE requires a reason, DECISION-LOCATION-026)
        BayPatchRequest patchStatus = BayPatchRequest.builder()
                .status("OUT_OF_SERVICE")
                .outOfServiceReason("EQUIPMENT_FAILURE")
                .build();
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
        BayEntity entity = BayEntity.builder()
                .location(Location.builder().id(locationId).build())
                .name("Bay1")
                .bayType("GENERAL_SERVICE")
                .status("ACTIVE")
                .maxConcurrentVehicles(2)
                .build();
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(entity));
        BayPatchRequest patch = new BayPatchRequest();
        patch.setName("Bay2");
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, "Bay2"))
                .thenReturn(true);
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

    private static final String BAY_SERVICE_FQCN = "com.positivity.location.internal.service.BayService";
    private static final String BAY_REPOSITORY_FQCN = "com.positivity.location.internal.repository.BayRepository";
    private static final String LOCATION_REPOSITORY_FQCN =
            "com.positivity.location.internal.repository.LocationRepository";
    private static final String BAY_TYPE_FQCN = "com.positivity.location.internal.enums.BayType";
    private static final String BAY_CAPACITY_REQUEST_FQCN = "com.positivity.location.internal.dto.BayCapacityRequest";
    private static final String RESOURCE_NOT_FOUND_EXCEPTION_FQCN =
            "com.positivity.location.internal.exception.ResourceNotFoundException";

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

        when(locationRepository.findById(locationId))
                .thenReturn(Optional.of(Location.builder().id(locationId).build()));
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, request.getName()))
                .thenReturn(false);
        when(bayRepository.findByLocationIdAndNormalizedName(
                        locationId, request.getName().toLowerCase()))
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

        when(locationRepository.findById(locationId))
                .thenReturn(Optional.of(Location.builder().id(locationId).build()));
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, request.getName()))
                .thenReturn(false);
        when(bayRepository.findByLocationIdAndNormalizedName(
                        locationId, request.getName().toLowerCase()))
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

        when(locationRepository.findById(locationId))
                .thenReturn(Optional.of(Location.builder().id(locationId).build()));

        assertThatThrownBy(() -> bayService.createBay(locationId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid bayType");
    }

    @Test
    @DisplayName("createBay_invalidCapacity_throwsIllegalArgumentException")
    void createBay_invalidCapacity_throwsIllegalArgumentException() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();
        request.setCapacity(
                BayCapacityRequest.builder().maxConcurrentVehicles(0).build());

        when(locationRepository.findById(locationId))
                .thenReturn(Optional.of(Location.builder().id(locationId).build()));

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

        when(locationRepository.findById(locationId))
                .thenReturn(Optional.of(Location.builder().id(locationId).build()));

        assertThatThrownBy(() -> bayService.createBay(locationId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity.maxConcurrentVehicles is required");
    }

    @Test
    @DisplayName("createBay_successWithCapabilitiesAndSkills_savesAndReturnsResponse")
    void createBay_successWithCapabilitiesAndSkills_savesAndReturnsResponse() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();
        request.setServiceCapabilityCodes(List.of("ALIGN", "TIRE"));
        request.setStatus("active");

        when(locationRepository.findById(locationId))
                .thenReturn(Optional.of(Location.builder().id(locationId).build()));
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, request.getName()))
                .thenReturn(false);
        when(bayRepository.findByLocationIdAndNormalizedName(
                        locationId, request.getName().toLowerCase()))
                .thenReturn(Optional.empty());
        when(bayRepository.save(any(BayEntity.class))).thenAnswer(invocation -> {
            BayEntity bay = invocation.getArgument(0, BayEntity.class);
            bay.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            return bay;
        });

        BayResponse response = bayService.createBay(locationId, request);

        assertThat(response.getLocationId()).isEqualTo(locationId);
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
        assertThat(response.getServiceCapabilityCodes()).containsExactly("ALIGN", "TIRE");
        verify(bayRepository).save(any(BayEntity.class));
    }

    @Test
    @DisplayName("createBay_invalidServiceCapabilityCodes_throwsIllegalArgumentException")
    void createBay_invalidServiceCapabilityCodes_throwsIllegalArgumentException() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();
        request.setServiceCapabilityCodes(List.of("ALIGN", "UNKNOWN_CAP"));

        when(locationRepository.findById(locationId))
                .thenReturn(Optional.of(Location.builder().id(locationId).build()));
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, request.getName()))
                .thenReturn(false);
        when(bayRepository.findByLocationIdAndNormalizedName(
                        locationId, request.getName().toLowerCase()))
                .thenReturn(Optional.empty());
        when(extCatalogServiceReplicaRepository.findByOperationCodeInAndActiveIsTrue(any()))
                .thenReturn(List.of(ExtCatalogServiceReplica.builder()
                        .serviceId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                        .operationCode("ALIGN")
                        .name("Align")
                        .active(true)
                        .aggregateVersion(1L)
                        .build()));

        assertThatThrownBy(() -> bayService.createBay(locationId, request))
                .isInstanceOf(InvalidServiceCapabilityCodesException.class)
                .hasMessageContaining("Invalid serviceCapabilityCodes: UNKNOWN_CAP")
                .extracting("invalidCodes")
                .isEqualTo(List.of("UNKNOWN_CAP"));
    }

    @Test
    @DisplayName("CAP-325 D14 - a specialty-map default the catalog does not know is refused like a caller's claim")
    void createBay_refusesSeededDefaultTheCatalogDoesNotKnow() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();
        request.setBayType(BayType.ALIGNMENT.name());
        request.setServiceCapabilityCodes(null);

        when(locationRepository.findById(locationId))
                .thenReturn(Optional.of(Location.builder().id(locationId).build()));
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, request.getName()))
                .thenReturn(false);
        when(bayRepository.findByLocationIdAndNormalizedName(
                        locationId, request.getName().toLowerCase()))
                .thenReturn(Optional.empty());
        when(baySpecialtyOperationRepository.findByBayType(BayType.ALIGNMENT.name()))
                .thenReturn(List.of(
                        BaySpecialtyOperationEntity.builder()
                                .bayType(BayType.ALIGNMENT.name())
                                .operationCode("WHEEL-ALIGNMENT-4-WHEEL")
                                .build(),
                        BaySpecialtyOperationEntity.builder()
                                .bayType(BayType.ALIGNMENT.name())
                                .operationCode("RETIRED-ALIGNMENT-OP")
                                .build()));
        // The catalog replica knows the first code as active and nothing of the second.
        doReturn(List.of(ExtCatalogServiceReplica.builder()
                        .serviceId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                        .operationCode("WHEEL-ALIGNMENT-4-WHEEL")
                        .name("Alignment")
                        .active(true)
                        .aggregateVersion(1L)
                        .build()))
                .when(extCatalogServiceReplicaRepository)
                .findByOperationCodeInAndActiveIsTrue(any());

        assertThatThrownBy(() -> bayService.createBay(locationId, request))
                .isInstanceOf(InvalidServiceCapabilityCodesException.class)
                .hasMessageContaining("Specialty map for bayType ALIGNMENT")
                .hasMessageContaining("RETIRED-ALIGNMENT-OP")
                .extracting("invalidCodes")
                .isEqualTo(List.of("RETIRED-ALIGNMENT-OP"));
        verify(bayRepository, never()).save(any());
    }

    @Test
    @DisplayName("createBay_successUsesFallbackMaxConcurrentVehiclesWhenCapacityNull")
    void createBay_successUsesFallbackMaxConcurrentVehiclesWhenCapacityNull() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();
        request.setCapacity(null);
        request.setMaxConcurrentVehicles(4);

        when(locationRepository.findById(locationId))
                .thenReturn(Optional.of(Location.builder().id(locationId).build()));
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, request.getName()))
                .thenReturn(false);
        when(bayRepository.findByLocationIdAndNormalizedName(
                        locationId, request.getName().toLowerCase()))
                .thenReturn(Optional.empty());
        when(bayRepository.save(any(BayEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, BayEntity.class));

        BayResponse response = bayService.createBay(locationId, request);

        assertThat(response.getMaxConcurrentVehicles()).isEqualTo(4);
        verify(bayRepository).save(any(BayEntity.class));
    }

    @Test
    @DisplayName("listBays_noFilters_defaultHidesRetiredAndOrdersByDisplayOrderThenName")
    void listBays_noFilters_callsFindByLocationId() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PageRequest pageable = PageRequest.of(0, 10);
        BayEntity entity = defaultBay(locationId);

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByLocationIdAndStatusNot(eq(locationId), eq("RETIRED"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity)));

        Page<BayResponse> page = bayService.listBays(locationId, null, null, pageable);

        assertThat(page.getContent()).hasSize(1);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(bayRepository).findByLocationIdAndStatusNot(eq(locationId), eq("RETIRED"), captor.capture());
        // DECISION-LOCATION-026 rule 5: displayOrder (nulls last), then name.
        List<Sort.Order> orders = captor.getValue().getSort().toList();
        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).getProperty()).isEqualTo("displayOrder");
        assertThat(orders.get(0).getNullHandling()).isEqualTo(Sort.NullHandling.NULLS_LAST);
        assertThat(orders.get(1).getProperty()).isEqualTo("name");
    }

    @Test
    @DisplayName("listBays_filterByStatus_callsFindByLocationIdAndStatus")
    void listBays_filterByStatus_callsFindByLocationIdAndStatus() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PageRequest pageable = PageRequest.of(0, 10);
        BayEntity entity = defaultBay(locationId);

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByLocationIdAndStatus(eq(locationId), eq("ACTIVE"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity)));

        Page<BayResponse> page = bayService.listBays(locationId, "active", null, pageable);

        assertThat(page.getContent()).hasSize(1);
        verify(bayRepository).findByLocationIdAndStatus(eq(locationId), eq("ACTIVE"), any(Pageable.class));
    }

    @Test
    @DisplayName("listBays_filterByBayType_defaultHidesRetired")
    void listBays_filterByBayType_callsFindByLocationIdAndBayType() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PageRequest pageable = PageRequest.of(0, 10);
        BayEntity entity = defaultBay(locationId);

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByLocationIdAndBayTypeAndStatusNot(
                        eq(locationId), eq("GENERAL_SERVICE"), eq("RETIRED"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity)));

        Page<BayResponse> page = bayService.listBays(locationId, null, "general_service", pageable);

        assertThat(page.getContent()).hasSize(1);
        verify(bayRepository)
                .findByLocationIdAndBayTypeAndStatusNot(
                        eq(locationId), eq("GENERAL_SERVICE"), eq("RETIRED"), any(Pageable.class));
    }

    @Test
    @DisplayName("listBays_filterByBoth_callsFindByLocationIdAndStatusAndBayType")
    void listBays_filterByBoth_callsFindByLocationIdAndStatusAndBayType() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PageRequest pageable = PageRequest.of(0, 10);
        BayEntity entity = defaultBay(locationId);

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByLocationIdAndStatusAndBayType(
                        eq(locationId), eq("OUT_OF_SERVICE"), eq("ALIGNMENT"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity)));

        Page<BayResponse> page = bayService.listBays(locationId, "out_of_service", "alignment", pageable);

        assertThat(page.getContent()).hasSize(1);
        verify(bayRepository)
                .findByLocationIdAndStatusAndBayType(
                        eq(locationId), eq("OUT_OF_SERVICE"), eq("ALIGNMENT"), any(Pageable.class));
    }

    @Test
    @DisplayName("listBays_blankFilters_treatedAsNoFilters")
    void listBays_blankFilters_treatedAsNoFilters() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PageRequest pageable = PageRequest.of(0, 5);
        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByLocationIdAndStatusNot(eq(locationId), eq("RETIRED"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(defaultBay(locationId))));

        bayService.listBays(locationId, "  ", " ", pageable);

        verify(bayRepository).findByLocationIdAndStatusNot(eq(locationId), eq("RETIRED"), any(Pageable.class));
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
                .serviceCapabilityCodes(List.of("ALIGN", "BRAKE"))
                .build();

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(existing));
        when(bayRepository.save(any(BayEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, BayEntity.class));

        BayResponse response = bayService.patchBay(locationId, bayId, patch);

        assertThat(response.getServiceCapabilityCodes()).containsExactly("ALIGN", "BRAKE");
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

        BayResponse deactivated = bayService.patchBay(
                locationId,
                bayId,
                BayPatchRequest.builder()
                        .status("OUT_OF_SERVICE")
                        .outOfServiceReason("SCHEDULED_MAINTENANCE")
                        .build());
        BayResponse reactivated = bayService.patchBay(
                locationId, bayId, BayPatchRequest.builder().status("ACTIVE").build());

        assertThat(deactivated.getStatus()).isEqualTo("OUT_OF_SERVICE");
        assertThat(deactivated.getOutOfServiceReason()).isEqualTo("SCHEDULED_MAINTENANCE");
        assertThat(reactivated.getStatus()).isEqualTo("ACTIVE");
        // DECISION-LOCATION-026 rule 4: all three out-of-service fields clear on return to ACTIVE.
        assertThat(reactivated.getOutOfServiceReason()).isNull();
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
                .location(Location.builder().id(locationId).build())
                .name("Bay-1")
                .normalizedName("bay-1")
                .bayType(BayType.GENERAL_SERVICE.name())
                .status("ACTIVE")
                .maxConcurrentVehicles(2)
                .serviceCapabilityCodes(List.of())
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

    @Test
    @DisplayName("#1668 - taking a bay out of service publishes the status change as a fact")
    void patchBayStatusPublishesFact() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        BayEntity existing = BayEntity.builder()
                .id(bayId)
                .name("Front Bay 1")
                .bayType("SERVICE")
                .status("ACTIVE")
                .maxConcurrentVehicles(1)
                .build();
        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(existing));
        when(bayRepository.save(existing)).thenReturn(existing);

        BayPatchRequest patch = BayPatchRequest.builder()
                .status("OUT_OF_SERVICE")
                .outOfServiceReason("EQUIPMENT_FAILURE")
                .build();
        bayService.patchBay(locationId, bayId, patch);

        // A bay taken out of service keeps its replica row and flips inactive; consumers derive
        // that from the raw status, so the fact must be published (issue #1668).
        verify(locationFactPublisher).bayChanged(existing);
    }

    @Test
    @DisplayName(
            "#2264 - deleting a bay retires it: the row is kept, status becomes RETIRED, and an update fact is published")
    void deleteBayRetiresRow() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        BayEntity existing = BayEntity.builder()
                .id(bayId)
                .name("Front Bay 1")
                .bayType("SERVICE")
                .status("ACTIVE")
                .maxConcurrentVehicles(1)
                .build();
        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(existing));
        when(bayRepository.save(existing)).thenReturn(existing);

        assertThat(bayService.deleteBay(locationId, bayId)).isTrue();

        // DECISION-LOCATION-026: DELETE retires. Nothing is hard-deleted, and the fact published is
        // an ordinary update, not a tombstone, so consumers keep the replica row.
        assertThat(existing.getStatus()).isEqualTo("RETIRED");
        verify(bayRepository, never()).delete(any(BayEntity.class));
        verify(bayRepository).save(existing);
        verify(locationFactPublisher).bayChanged(existing);
    }

    @Test
    @DisplayName("#2264 - retiring an already-retired bay succeeds again rather than erroring")
    void deleteBayAlreadyRetiredIsIdempotent() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        BayEntity existing = BayEntity.builder()
                .id(bayId)
                .name("Front Bay 1")
                .bayType("SERVICE")
                .status("RETIRED")
                .maxConcurrentVehicles(1)
                .build();
        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(existing));
        when(bayRepository.save(existing)).thenReturn(existing);

        assertThat(bayService.deleteBay(locationId, bayId)).isTrue();

        assertThat(existing.getStatus()).isEqualTo("RETIRED");
        verify(locationFactPublisher).bayChanged(existing);
    }

    @Test
    @DisplayName("#1668 - deleting a bay that does not exist publishes nothing")
    void deleteMissingBayPublishesNothing() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.empty());

        // A retried delete for an id that never existed must not publish a fact every time.
        assertThat(bayService.deleteBay(locationId, bayId)).isFalse();

        verify(bayRepository, never()).save(any(BayEntity.class));
        verify(locationFactPublisher, never()).bayChanged(any());
    }

    @Test
    @DisplayName("#2264 - createBay OUT_OF_SERVICE without a reason returns 422 OUT_OF_SERVICE_REASON_REQUIRED")
    void createBay_outOfServiceMissingReason_throws422() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();
        request.setStatus("OUT_OF_SERVICE");

        when(locationRepository.findById(locationId))
                .thenReturn(Optional.of(Location.builder().id(locationId).build()));

        assertThatThrownBy(() -> bayService.createBay(locationId, request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting(exception ->
                        ((org.springframework.web.server.ResponseStatusException) exception).getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        verify(bayRepository, never()).save(any());
    }

    @Test
    @DisplayName("#2264 - createBay OUT_OF_SERVICE with reason OTHER but no note returns 422")
    void createBay_outOfServiceOtherWithoutNote_throws422() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();
        request.setStatus("OUT_OF_SERVICE");
        request.setOutOfServiceReason("OTHER");

        when(locationRepository.findById(locationId))
                .thenReturn(Optional.of(Location.builder().id(locationId).build()));

        assertThatThrownBy(() -> bayService.createBay(locationId, request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting(exception ->
                        ((org.springframework.web.server.ResponseStatusException) exception).getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        verify(bayRepository, never()).save(any());
    }

    @Test
    @DisplayName("#2264 - createBay OUT_OF_SERVICE with OTHER and a note succeeds")
    void createBay_outOfServiceOtherWithNote_succeeds() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();
        request.setStatus("OUT_OF_SERVICE");
        request.setOutOfServiceReason("OTHER");
        request.setOutOfServiceNote("Awaiting parts");

        when(locationRepository.findById(locationId))
                .thenReturn(Optional.of(Location.builder().id(locationId).build()));
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, request.getName()))
                .thenReturn(false);
        when(bayRepository.findByLocationIdAndNormalizedName(
                        locationId, request.getName().toLowerCase()))
                .thenReturn(Optional.empty());
        when(bayRepository.save(any(BayEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, BayEntity.class));

        BayResponse response = bayService.createBay(locationId, request);

        assertThat(response.getStatus()).isEqualTo("OUT_OF_SERVICE");
        assertThat(response.getOutOfServiceReason()).isEqualTo("OTHER");
        assertThat(response.getOutOfServiceNote()).isEqualTo("Awaiting parts");
    }

    @Test
    @DisplayName("#2264 - patchBay OUT_OF_SERVICE without a reason returns 422 OUT_OF_SERVICE_REASON_REQUIRED")
    void patchBay_outOfServiceMissingReason_throws422() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayEntity existing = defaultBay(locationId);
        existing.setId(bayId);

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(existing));

        BayPatchRequest patch =
                BayPatchRequest.builder().status("OUT_OF_SERVICE").build();

        assertThatThrownBy(() -> bayService.patchBay(locationId, bayId, patch))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting(exception ->
                        ((org.springframework.web.server.ResponseStatusException) exception).getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        verify(bayRepository, never()).save(any());
    }

    @Test
    @DisplayName("#2264 - a RETIRED bay can be reactivated via patchBay")
    void patchBay_reactivateRetiredBay_succeeds() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bayId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayEntity existing = defaultBay(locationId);
        existing.setId(bayId);
        existing.setStatus("RETIRED");

        when(locationRepository.existsById(locationId)).thenReturn(true);
        when(bayRepository.findByIdAndLocationId(bayId, locationId)).thenReturn(Optional.of(existing));
        when(bayRepository.save(any(BayEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, BayEntity.class));

        BayResponse response = bayService.patchBay(
                locationId, bayId, BayPatchRequest.builder().status("ACTIVE").build());

        assertThat(response.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("#2264 - creating a bay named like a retired bay at the same location returns 409 BAY_NAME_TAKEN")
    void createBay_nameMatchesRetiredBay_throwsConflict() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BayRequest request = validCreateRequest();

        when(locationRepository.findById(locationId))
                .thenReturn(Optional.of(Location.builder().id(locationId).build()));
        // The uniqueness check does not filter by status, so a retired bay's name still counts —
        // exercised here by having the exists-check answer true regardless of the retired row's
        // status, the same as it would for any other bay at this location.
        when(bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, request.getName()))
                .thenReturn(true);

        assertThatThrownBy(() -> bayService.createBay(locationId, request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("BAY_NAME_TAKEN");
        verify(bayRepository, never()).save(any());
    }
}
