package com.positivity.location.service;

import com.positivity.location.internal.client.LocationInventoryInquiryClient;
import com.positivity.location.internal.dto.StorageLocationPatchRequest;
import com.positivity.location.internal.dto.StorageLocationRequest;
import com.positivity.location.internal.dto.StorageLocationResponse;
import com.positivity.location.internal.entity.StorageLocationEntity;
import com.positivity.location.internal.enums.StorageLocationStatus;
import com.positivity.location.internal.enums.StorageLocationType;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.StorageLocationRepository;
import com.positivity.location.internal.service.StorageLocationServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * RED unit tests for StorageLocation topology behavior — create, list, get,
 * deactivate.
 *
 * Covers Story #39: Topology: Create Storage Locations
 * (Floor/Shelf/Bin/Cage/Truck).
 * Business rules: unique name/barcode per site, DAG hierarchy (no cycles),
 * parent must belong to same site, deactivation requires destination when
 * inventory > 0.
 *
 * Issue: CAP-214 #39
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageLocationServiceTest")
class StorageLocationServiceTest {

    @Mock
    private StorageLocationRepository storageLocationRepository;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private StorageLocationInventoryTransferService storageLocationInventoryTransferService;

    @Mock
    private LocationInventoryInquiryClient locationInventoryInquiryClient;

    @InjectMocks
    private StorageLocationServiceImpl storageLocationService;

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    /**
     * Happy path: a valid request creates a StorageLocation and returns the
     * response DTO.
     *
     * @throws ResponseStatusException not expected
     */
    @Test
    @DisplayName("#39 - valid create returns response with ACTIVE status")
    void createStorageLocation_success_returnsResponse() {
        UUID siteId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        StorageLocationRequest request = validRequest("Bin-A1", "BAR-001", StorageLocationType.BIN, null);

        when(locationRepository.existsById(siteId)).thenReturn(true);
        when(storageLocationRepository.existsByNameIgnoreCaseAndSiteId("Bin-A1", siteId)).thenReturn(false);
        when(storageLocationRepository.existsByBarcodeAndSiteId("BAR-001", siteId)).thenReturn(false);
        when(storageLocationRepository.saveAndFlush(any(StorageLocationEntity.class)))
                .thenAnswer(inv -> {
                    StorageLocationEntity e = inv.getArgument(0);
                    e.setId(newId);
                    return e;
                });

        StorageLocationResponse response = storageLocationService.createStorageLocation(siteId, request);

        assertThat(response.getId()).isEqualTo(newId);
        assertThat(response.getName()).isEqualTo("Bin-A1");
        assertThat(response.getType()).isEqualTo(StorageLocationType.BIN);
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("#39 - createStorageLocation persists and returns capacity/temperature metadata")
    void createStorageLocation_withCapacityAndTemperature_returnsMetadata() {
        UUID siteId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        StorageLocationRequest request = validRequest("Bin-A2", "BAR-010", StorageLocationType.BIN, null);
        request.setCapacity(Map.of("unitCount", 50, "weightKg", 100));
        request.setTemperature(Map.of("minCelsius", 2, "maxCelsius", 8));

        when(locationRepository.existsById(siteId)).thenReturn(true);
        when(storageLocationRepository.existsByNameIgnoreCaseAndSiteId("Bin-A2", siteId)).thenReturn(false);
        when(storageLocationRepository.existsByBarcodeAndSiteId("BAR-010", siteId)).thenReturn(false);
        when(storageLocationRepository.saveAndFlush(any(StorageLocationEntity.class)))
                .thenAnswer(inv -> {
                    StorageLocationEntity e = inv.getArgument(0);
                    e.setId(newId);
                    return e;
                });

        StorageLocationResponse response = storageLocationService.createStorageLocation(siteId, request);

        assertThat(response.getCapacity()).containsEntry("unitCount", 50);
        assertThat(response.getTemperature()).containsEntry("minCelsius", 2);
    }

    /**
     * Duplicate name within the same site must result in 409 DUPLICATE_NAME.
     */
    @Test
    @DisplayName("#39 - duplicate name per site returns 409 DUPLICATE_NAME")
    void createStorageLocation_duplicateName_throwsConflict() {
        UUID siteId = UUID.randomUUID();
        StorageLocationRequest request = validRequest("Shelf-A", "BAR-002", StorageLocationType.SHELF, null);

        when(locationRepository.existsById(siteId)).thenReturn(true);
        when(storageLocationRepository.existsByNameIgnoreCaseAndSiteId("Shelf-A", siteId)).thenReturn(true);

        assertThatThrownBy(() -> storageLocationService.createStorageLocation(siteId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("DUPLICATE_NAME");
                });
    }

    /**
     * Duplicate barcode within the same site must result in 409 DUPLICATE_BARCODE.
     */
    @Test
    @DisplayName("#39 - duplicate barcode per site returns 409 DUPLICATE_BARCODE")
    void createStorageLocation_duplicateBarcode_throwsConflict() {
        UUID siteId = UUID.randomUUID();
        StorageLocationRequest request = validRequest("Floor-B", "BAR-DUP", StorageLocationType.FLOOR, null);

        when(locationRepository.existsById(siteId)).thenReturn(true);
        when(storageLocationRepository.existsByNameIgnoreCaseAndSiteId(any(), eq(siteId))).thenReturn(false);
        when(storageLocationRepository.existsByBarcodeAndSiteId("BAR-DUP", siteId)).thenReturn(true);

        assertThatThrownBy(() -> storageLocationService.createStorageLocation(siteId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("DUPLICATE_BARCODE");
                });
    }

    /**
     * Parent must belong to the same site; cross-site parent reference must result
     * in 400.
     */
    @Test
    @DisplayName("#39 - parentStorageLocationId from different site returns 400")
    void createStorageLocation_parentNotSameSite_throwsBadRequest() {
        UUID siteId = UUID.randomUUID();
        UUID differentSiteId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        StorageLocationRequest request = validRequest("Truck-Y", "BAR-004", StorageLocationType.TRUCK, parentId);

        StorageLocationEntity parent = StorageLocationEntity.builder()
                .id(parentId)
                .siteId(differentSiteId)
                .build();

        when(locationRepository.existsById(siteId)).thenReturn(true);
        when(storageLocationRepository.existsByNameIgnoreCaseAndSiteId(any(), eq(siteId))).thenReturn(false);
        when(storageLocationRepository.existsByBarcodeAndSiteId(any(), eq(siteId))).thenReturn(false);
        when(storageLocationRepository.findById(parentId)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> storageLocationService.createStorageLocation(siteId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    // -------------------------------------------------------------------------
    // Patch / reparent — cycle detection
    // -------------------------------------------------------------------------

    /**
     * Setting a parent that would create a cycle in the DAG must result in 409
     * CYCLE_DETECTED.
     * Verified via patch (reparenting), which is the only mutation that can
     * introduce a cycle.
     */
    @Test
    @DisplayName("#39 - patching parentStorageLocationId to create a cycle returns 409 CYCLE_DETECTED")
    void updateStorageLocation_cyclicParent_throwsCycleDetected() {
        UUID siteId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        // The existing node (existingId) is an ancestor of the proposed parent
        // (parentId),
        // so setting parentId as existingId's parent would form a cycle.
        StorageLocationEntity existing = StorageLocationEntity.builder()
                .id(existingId)
                .siteId(siteId)
                .name("Floor-C")
                .type(StorageLocationType.FLOOR)
                .status(StorageLocationStatus.ACTIVE)
                .build();

        StorageLocationEntity parent = StorageLocationEntity.builder()
                .id(parentId)
                .siteId(siteId)
                .parentStorageLocationId(existingId) // parent's ancestor is existing → cycle
                .build();

        StorageLocationPatchRequest patch = StorageLocationPatchRequest.builder()
                .parentStorageLocationId(parentId)
                .build();

        when(storageLocationRepository.findByIdAndSiteId(existingId, siteId)).thenReturn(Optional.of(existing));
        when(storageLocationRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(storageLocationRepository.existsCycleForParent(existingId, parentId)).thenReturn(true);

        assertThatThrownBy(() -> storageLocationService.patchStorageLocation(siteId, existingId, patch))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("CYCLE_DETECTED");
                });
    }

    // -------------------------------------------------------------------------
    // Get
    // -------------------------------------------------------------------------

    /**
     * Requesting a StorageLocation that does not exist must result in 404.
     */
    @Test
    @DisplayName("#39 - getStorageLocation for unknown id returns 404")
    void getStorageLocation_notFound_throwsNotFound() {
        UUID siteId = UUID.randomUUID();
        UUID id = UUID.randomUUID();

        when(storageLocationRepository.findByIdAndSiteId(id, siteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storageLocationService.getStorageLocation(siteId, id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    @DisplayName("#39 - getStorageLocationValidation returns exists/active state by id")
    void getStorageLocationValidation_returnsValidationPayload() {
        UUID siteId = UUID.randomUUID();
        UUID storageId = UUID.randomUUID();
        StorageLocationEntity entity = StorageLocationEntity.builder()
                .id(storageId)
                .siteId(siteId)
                .status(StorageLocationStatus.ACTIVE)
                .capacity("{\"unitCount\":42}")
                .build();
        when(storageLocationRepository.findById(storageId)).thenReturn(Optional.of(entity));

        var result = storageLocationService.getStorageLocationValidation(storageId);

        assertThat(result.isExists()).isTrue();
        assertThat(result.isActive()).isTrue();
        assertThat(result.getStorageLocationId()).isEqualTo(storageId);
        assertThat(result.getSiteId()).isEqualTo(siteId);
        assertThat(result.getMaxUnitCapacity()).isEqualTo(42);
    }

    // -------------------------------------------------------------------------
    // List
    // -------------------------------------------------------------------------

    /**
     * Listing with a type filter should delegate the filter to the repository and
     * return only matching entries.
     */
    @Test
    @DisplayName("#39 - listStorageLocations filtered by type returns matching page")
    void listStorageLocations_filterByType_returnsFiltered() {
        UUID siteId = UUID.randomUUID();
        StorageLocationEntity entity = StorageLocationEntity.builder()
                .id(UUID.randomUUID())
                .name("BIN-1")
                .type(StorageLocationType.BIN)
                .barcode("BIN-BC-001")
                .siteId(siteId)
                .status(StorageLocationStatus.ACTIVE)
                .build();

        Page<StorageLocationEntity> page = new PageImpl<>(List.of(entity));
        when(storageLocationRepository.findBySiteIdAndType(siteId, StorageLocationType.BIN, PageRequest.of(0, 20)))
                .thenReturn(page);

        Page<StorageLocationResponse> result = storageLocationService.listStorageLocations(siteId,
                StorageLocationType.BIN, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getType()).isEqualTo(StorageLocationType.BIN);
    }

    @Test
    @DisplayName("#39 - listStorageLocations without type filter uses site-only query")
    void listStorageLocations_withoutType_returnsPage() {
        UUID siteId = UUID.randomUUID();
        StorageLocationEntity entity = StorageLocationEntity.builder()
                .id(UUID.randomUUID())
                .name("NO-TYPE-1")
                .type(StorageLocationType.SHELF)
                .siteId(siteId)
                .status(StorageLocationStatus.ACTIVE)
                .build();

        Page<StorageLocationEntity> page = new PageImpl<>(List.of(entity));
        when(storageLocationRepository.findBySiteId(siteId, PageRequest.of(0, 10))).thenReturn(page);

        Page<StorageLocationResponse> result = storageLocationService.listStorageLocations(siteId, null,
                null, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getName()).isEqualTo("NO-TYPE-1");
    }

    // -------------------------------------------------------------------------
    // Deactivate
    // -------------------------------------------------------------------------

    /**
     * Deactivating a storage location with zero inventory should succeed without
     * requiring a
     * destination.
     */
    @Test
    @DisplayName("#39 - deactivate with no inventory succeeds, status becomes INACTIVE")
    void deactivateStorageLocation_noInventory_succeeds() {
        UUID siteId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        StorageLocationEntity entity = StorageLocationEntity.builder()
                .id(id)
                .name("Shelf-B2")
                .type(StorageLocationType.SHELF)
                .barcode("SHF-BC-002")
                .siteId(siteId)
                .status(StorageLocationStatus.ACTIVE)
                .build();

        when(storageLocationRepository.findByIdAndSiteId(id, siteId)).thenReturn(Optional.of(entity));
        when(locationInventoryInquiryClient.getOnHandQuantity(id)).thenReturn(0);
        when(storageLocationRepository.saveAndFlush(any(StorageLocationEntity.class)))
                .thenAnswer(inv -> {
                    StorageLocationEntity e = inv.getArgument(0);
                    e.setStatus(StorageLocationStatus.INACTIVE);
                    return e;
                });

        StorageLocationResponse response = storageLocationService.deactivateStorageLocation(siteId, id, null);

        assertThat(response.getStatus()).isEqualTo("INACTIVE");
    }

    /**
     * Deactivating a storage location that holds inventory without supplying a
     * destinationStorageLocationId must result in 422 DESTINATION_REQUIRED.
     */
    @Test
    @DisplayName("#39 - deactivate with inventory but no destination returns 422 DESTINATION_REQUIRED")
    void deactivateStorageLocation_withInventory_noDestination_throwsValidationError() {
        UUID siteId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        StorageLocationEntity entity = StorageLocationEntity.builder()
                .id(id)
                .name("Floor-C3")
                .type(StorageLocationType.FLOOR)
                .barcode("FLR-BC-003")
                .siteId(siteId)
                .status(StorageLocationStatus.ACTIVE)
                .build();

        when(storageLocationRepository.findByIdAndSiteId(id, siteId)).thenReturn(Optional.of(entity));
        when(locationInventoryInquiryClient.getOnHandQuantity(id)).thenReturn(5);

        assertThatThrownBy(() -> storageLocationService.deactivateStorageLocation(siteId, id, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(rse.getReason()).contains("DESTINATION_REQUIRED");
                });
    }

    @Test
    @DisplayName("#39 - deactivate with inventory transfers stock to active destination and inactivates source")
    void deactivateStorageLocation_withInventoryAndDestination_transfersInventory() {
        UUID siteId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        StorageLocationEntity source = StorageLocationEntity.builder()
                .id(sourceId)
                .name("Floor-C3")
                .type(StorageLocationType.FLOOR)
                .siteId(siteId)
                .status(StorageLocationStatus.ACTIVE)
                .build();
        StorageLocationEntity destination = StorageLocationEntity.builder()
                .id(destinationId)
                .name("Shelf-Z9")
                .type(StorageLocationType.SHELF)
                .siteId(siteId)
                .status(StorageLocationStatus.ACTIVE)
                .build();

        when(storageLocationRepository.findByIdAndSiteId(sourceId, siteId)).thenReturn(Optional.of(source));
        when(storageLocationRepository.findByIdAndSiteId(destinationId, siteId)).thenReturn(Optional.of(destination));
        when(locationInventoryInquiryClient.getOnHandQuantity(sourceId)).thenReturn(5);
        when(storageLocationRepository.saveAndFlush(any(StorageLocationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        StorageLocationResponse response = storageLocationService.deactivateStorageLocation(siteId, sourceId,
                destinationId);

        assertThat(response.getStatus()).isEqualTo("INACTIVE");
        assertThat(response.getInventoryCount()).isZero();
    }

    @Test
    @DisplayName("#39 - deactivate with inventory and missing destination location returns 404")
    void deactivateStorageLocation_withInventoryAndUnknownDestination_throwsNotFound() {
        UUID siteId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        StorageLocationEntity source = StorageLocationEntity.builder()
                .id(sourceId)
                .name("Floor-C3")
                .type(StorageLocationType.FLOOR)
                .siteId(siteId)
                .status(StorageLocationStatus.ACTIVE)
                .build();

        when(storageLocationRepository.findByIdAndSiteId(sourceId, siteId)).thenReturn(Optional.of(source));
        when(locationInventoryInquiryClient.getOnHandQuantity(sourceId)).thenReturn(2);
        when(storageLocationRepository.findByIdAndSiteId(destinationId, siteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storageLocationService.deactivateStorageLocation(siteId, sourceId, destinationId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(rse.getReason()).contains("DESTINATION_NOT_FOUND");
                });
    }

    @Test
    @DisplayName("#39 - deactivate with inventory and inactive destination returns 422")
    void deactivateStorageLocation_withInventoryAndInactiveDestination_throwsValidationError() {
        UUID siteId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        StorageLocationEntity source = StorageLocationEntity.builder()
                .id(sourceId)
                .name("Floor-C3")
                .type(StorageLocationType.FLOOR)
                .siteId(siteId)
                .status(StorageLocationStatus.ACTIVE)
                .build();
        StorageLocationEntity destination = StorageLocationEntity.builder()
                .id(destinationId)
                .name("Shelf-Z9")
                .type(StorageLocationType.SHELF)
                .siteId(siteId)
                .status(StorageLocationStatus.INACTIVE)
                .build();

        when(storageLocationRepository.findByIdAndSiteId(sourceId, siteId)).thenReturn(Optional.of(source));
        when(locationInventoryInquiryClient.getOnHandQuantity(sourceId)).thenReturn(2);
        when(storageLocationRepository.findByIdAndSiteId(destinationId, siteId)).thenReturn(Optional.of(destination));

        assertThatThrownBy(() -> storageLocationService.deactivateStorageLocation(siteId, sourceId, destinationId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(rse.getReason()).contains("DESTINATION_INACTIVE");
                });
    }

    // Issue CAP-214 #39: Additional targeted tests for uncovered
    // StorageLocationServiceImpl paths.

    @Test
    @DisplayName("#39 - createStorageLocation when site does not exist returns 404 SITE_NOT_FOUND")
    void createStorageLocation_siteMissing_throwsNotFound() {
        UUID siteId = UUID.randomUUID();
        StorageLocationRequest request = validRequest("Bin-X1", "BAR-X1", StorageLocationType.BIN, null);

        when(locationRepository.existsById(siteId)).thenReturn(false);

        assertThatThrownBy(() -> storageLocationService.createStorageLocation(siteId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(rse.getReason()).contains("SITE_NOT_FOUND");
                });
    }

    @Test
    @DisplayName("#39 - createStorageLocation with blank name returns 400")
    void createStorageLocation_blankName_throwsBadRequest() {
        UUID siteId = UUID.randomUUID();
        StorageLocationRequest request = validRequest("   ", "BAR-X2", StorageLocationType.BIN, null);

        when(locationRepository.existsById(siteId)).thenReturn(true);

        assertThatThrownBy(() -> storageLocationService.createStorageLocation(siteId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("name is required");
                });
    }

    @Test
    @DisplayName("#39 - createStorageLocation with null type returns 400")
    void createStorageLocation_missingType_throwsBadRequest() {
        UUID siteId = UUID.randomUUID();
        StorageLocationRequest request = validRequest("Bin-X3", "BAR-X3", null, null);

        when(locationRepository.existsById(siteId)).thenReturn(true);

        assertThatThrownBy(() -> storageLocationService.createStorageLocation(siteId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("type is required");
                });
    }

    @Test
    @DisplayName("#39 - createStorageLocation with missing parent returns 400 PARENT_NOT_FOUND")
    void createStorageLocation_parentNotFound_throwsBadRequest() {
        UUID siteId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        StorageLocationRequest request = validRequest("Bin-X4", "BAR-X4", StorageLocationType.BIN, parentId);

        when(locationRepository.existsById(siteId)).thenReturn(true);
        when(storageLocationRepository.existsByNameIgnoreCaseAndSiteId("Bin-X4", siteId)).thenReturn(false);
        when(storageLocationRepository.existsByBarcodeAndSiteId("BAR-X4", siteId)).thenReturn(false);
        when(storageLocationRepository.findById(parentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storageLocationService.createStorageLocation(siteId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("PARENT_NOT_FOUND");
                });
    }

    @Test
    @DisplayName("#39 - patchStorageLocation updates parent and status when valid")
    void patchStorageLocation_validPatch_updatesFields() {
        UUID siteId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        StorageLocationEntity existing = StorageLocationEntity.builder()
                .id(storageLocationId)
                .name("Bin-Old")
                .barcode("BAR-OLD")
                .type(StorageLocationType.BIN)
                .siteId(siteId)
                .status(StorageLocationStatus.ACTIVE)
                .build();

        StorageLocationEntity parent = StorageLocationEntity.builder()
                .id(parentId)
                .siteId(siteId)
                .build();

        StorageLocationPatchRequest patch = StorageLocationPatchRequest.builder()
                .name("Bin-New")
                .barcode("BAR-NEW")
                .parentStorageLocationId(parentId)
                .status(StorageLocationStatus.INACTIVE)
                .destinationStorageLocationId(UUID.randomUUID())
                .build();

        when(storageLocationRepository.findByIdAndSiteId(storageLocationId, siteId)).thenReturn(Optional.of(existing));
        when(locationInventoryInquiryClient.getOnHandQuantity(storageLocationId)).thenReturn(0);
        when(storageLocationRepository.existsByNameIgnoreCaseAndSiteIdAndIdNot("Bin-New", siteId, storageLocationId))
                .thenReturn(false);
        when(storageLocationRepository.existsByBarcodeAndSiteIdAndIdNot("BAR-NEW", siteId, storageLocationId))
                .thenReturn(false);
        when(storageLocationRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(storageLocationRepository.existsCycleForParent(storageLocationId, parentId)).thenReturn(false);
        when(storageLocationRepository.saveAndFlush(any(StorageLocationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        StorageLocationResponse response = storageLocationService.patchStorageLocation(siteId, storageLocationId,
                patch);

        assertThat(response.getName()).isEqualTo("Bin-New");
        assertThat(response.getBarcode()).isEqualTo("BAR-NEW");
        assertThat(response.getParentStorageLocationId()).isEqualTo(parentId);
        assertThat(response.getStatus()).isEqualTo("INACTIVE");
    }

    @Test
    @DisplayName("#39 - patchStorageLocation duplicate name returns 409 DUPLICATE_NAME")
    void patchStorageLocation_duplicateName_throwsConflict() {
        UUID siteId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();

        StorageLocationEntity existing = StorageLocationEntity.builder()
                .id(storageLocationId)
                .name("Bin-Old")
                .siteId(siteId)
                .status(StorageLocationStatus.ACTIVE)
                .build();

        StorageLocationPatchRequest patch = StorageLocationPatchRequest.builder()
                .name("Bin-Dup")
                .build();

        when(storageLocationRepository.findByIdAndSiteId(storageLocationId, siteId)).thenReturn(Optional.of(existing));
        when(storageLocationRepository.existsByNameIgnoreCaseAndSiteIdAndIdNot("Bin-Dup", siteId, storageLocationId))
                .thenReturn(true);

        assertThatThrownBy(() -> storageLocationService.patchStorageLocation(siteId, storageLocationId, patch))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("DUPLICATE_NAME");
                });
    }

    @Test
    @DisplayName("#39 - patchStorageLocation duplicate barcode returns 409 DUPLICATE_BARCODE")
    void patchStorageLocation_duplicateBarcode_throwsConflict() {
        UUID siteId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();

        StorageLocationEntity existing = StorageLocationEntity.builder()
                .id(storageLocationId)
                .name("Bin-Old")
                .barcode("BAR-OLD")
                .siteId(siteId)
                .status(StorageLocationStatus.ACTIVE)
                .build();

        StorageLocationPatchRequest patch = StorageLocationPatchRequest.builder()
                .barcode("BAR-DUP")
                .build();

        when(storageLocationRepository.findByIdAndSiteId(storageLocationId, siteId)).thenReturn(Optional.of(existing));
        when(storageLocationRepository.existsByBarcodeAndSiteIdAndIdNot("BAR-DUP", siteId, storageLocationId))
                .thenReturn(true);

        assertThatThrownBy(() -> storageLocationService.patchStorageLocation(siteId, storageLocationId, patch))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("DUPLICATE_BARCODE");
                });
    }

    @Test
    @DisplayName("#39 - patchStorageLocation self parent returns 409 CYCLE_DETECTED")
    void patchStorageLocation_selfParent_throwsConflict() {
        UUID siteId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();

        StorageLocationEntity existing = StorageLocationEntity.builder()
                .id(storageLocationId)
                .name("Bin-Old")
                .siteId(siteId)
                .status(StorageLocationStatus.ACTIVE)
                .build();

        StorageLocationPatchRequest patch = StorageLocationPatchRequest.builder()
                .parentStorageLocationId(storageLocationId)
                .build();

        when(storageLocationRepository.findByIdAndSiteId(storageLocationId, siteId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> storageLocationService.patchStorageLocation(siteId, storageLocationId, patch))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("CYCLE_DETECTED");
                });
    }

    @Test
    @DisplayName("#39 - patchStorageLocation unknown id returns 404")
    void patchStorageLocation_notFound_throwsNotFound() {
        UUID siteId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();

        StorageLocationPatchRequest patch = StorageLocationPatchRequest.builder()
                .name("Any")
                .build();

        when(storageLocationRepository.findByIdAndSiteId(storageLocationId, siteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storageLocationService.patchStorageLocation(siteId, storageLocationId, patch))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(rse.getReason()).contains("STORAGE_LOCATION_NOT_FOUND");
                });
    }

    @Test
    @DisplayName("#39 - patchStorageLocation parent not found returns 400 PARENT_NOT_FOUND")
    void patchStorageLocation_parentNotFound_throwsBadRequest() {
        UUID siteId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        StorageLocationEntity existing = StorageLocationEntity.builder()
                .id(storageLocationId)
                .siteId(siteId)
                .name("Bin-A")
                .status(StorageLocationStatus.ACTIVE)
                .build();

        StorageLocationPatchRequest patch = StorageLocationPatchRequest.builder()
                .parentStorageLocationId(parentId)
                .build();

        when(storageLocationRepository.findByIdAndSiteId(storageLocationId, siteId)).thenReturn(Optional.of(existing));
        when(storageLocationRepository.findById(parentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storageLocationService.patchStorageLocation(siteId, storageLocationId, patch))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("PARENT_NOT_FOUND");
                });
    }

    @Test
    @DisplayName("#39 - patchStorageLocation parent site mismatch returns 400 PARENT_SITE_MISMATCH")
    void patchStorageLocation_parentSiteMismatch_throwsBadRequest() {
        UUID siteId = UUID.randomUUID();
        UUID otherSiteId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        StorageLocationEntity existing = StorageLocationEntity.builder()
                .id(storageLocationId)
                .siteId(siteId)
                .name("Bin-A")
                .status(StorageLocationStatus.ACTIVE)
                .build();

        StorageLocationEntity parent = StorageLocationEntity.builder()
                .id(parentId)
                .siteId(otherSiteId)
                .build();

        StorageLocationPatchRequest patch = StorageLocationPatchRequest.builder()
                .parentStorageLocationId(parentId)
                .build();

        when(storageLocationRepository.findByIdAndSiteId(storageLocationId, siteId)).thenReturn(Optional.of(existing));
        when(storageLocationRepository.findById(parentId)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> storageLocationService.patchStorageLocation(siteId, storageLocationId, patch))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("PARENT_SITE_MISMATCH");
                });
    }

    @Test
    @DisplayName("#39 - patchStorageLocation detects cycle through ancestor traversal")
    void patchStorageLocation_cycleDetectedByTraversal_throwsConflict() {
        UUID siteId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID ancestorId = UUID.randomUUID();

        StorageLocationEntity existing = StorageLocationEntity.builder()
                .id(storageLocationId)
                .siteId(siteId)
                .name("Bin-A")
                .status(StorageLocationStatus.ACTIVE)
                .build();

        StorageLocationEntity parent = StorageLocationEntity.builder()
                .id(parentId)
                .siteId(siteId)
                .parentStorageLocationId(ancestorId)
                .build();

        StorageLocationEntity ancestor = StorageLocationEntity.builder()
                .id(ancestorId)
                .siteId(siteId)
                .parentStorageLocationId(storageLocationId)
                .build();

        StorageLocationPatchRequest patch = StorageLocationPatchRequest.builder()
                .parentStorageLocationId(parentId)
                .build();

        when(storageLocationRepository.findByIdAndSiteId(storageLocationId, siteId)).thenReturn(Optional.of(existing));
        when(storageLocationRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(storageLocationRepository.existsCycleForParent(storageLocationId, parentId)).thenReturn(false);
        when(storageLocationRepository.findById(ancestorId)).thenReturn(Optional.of(ancestor));

        assertThatThrownBy(() -> storageLocationService.patchStorageLocation(siteId, storageLocationId, patch))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("CYCLE_DETECTED");
                });
    }

    @Test
    @DisplayName("#39 - patchStorageLocation blank barcode clears existing barcode")
    void patchStorageLocation_blankBarcode_clearsValue() {
        UUID siteId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();

        StorageLocationEntity existing = StorageLocationEntity.builder()
                .id(storageLocationId)
                .siteId(siteId)
                .name("Bin-A")
                .barcode("HAS-BAR")
                .status(StorageLocationStatus.ACTIVE)
                .build();

        StorageLocationPatchRequest patch = StorageLocationPatchRequest.builder()
                .barcode("   ")
                .build();

        when(storageLocationRepository.findByIdAndSiteId(storageLocationId, siteId)).thenReturn(Optional.of(existing));
        when(storageLocationRepository.saveAndFlush(any(StorageLocationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        StorageLocationResponse response = storageLocationService.patchStorageLocation(siteId, storageLocationId,
                patch);

        assertThat(response.getBarcode()).isNull();
    }

    @Test
    @DisplayName("#39 - createStorageLocation trims blank barcode to null")
    void createStorageLocation_blankBarcode_normalizesToNull() {
        UUID siteId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        StorageLocationRequest request = validRequest("Bin-X5", "   ", StorageLocationType.BIN, null);

        when(locationRepository.existsById(siteId)).thenReturn(true);
        when(storageLocationRepository.existsByNameIgnoreCaseAndSiteId("Bin-X5", siteId)).thenReturn(false);
        when(storageLocationRepository.saveAndFlush(any(StorageLocationEntity.class)))
                .thenAnswer(inv -> {
                    StorageLocationEntity e = inv.getArgument(0);
                    e.setId(newId);
                    return e;
                });

        StorageLocationResponse response = storageLocationService.createStorageLocation(siteId, request);

        assertThat(response.getId()).isEqualTo(newId);
        assertThat(response.getBarcode()).isNull();
    }

    @Test
    @DisplayName("#39 - patchStorageLocation inactive with inventory and no destination returns 422")
    void patchStorageLocation_inactiveWithInventoryNoDestination_throwsValidationError() {
        UUID siteId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();

        StorageLocationEntity existing = StorageLocationEntity.builder()
                .id(storageLocationId)
                .name("Bin-Old")
                .siteId(siteId)
                .status(StorageLocationStatus.ACTIVE)
                .build();

        StorageLocationPatchRequest patch = StorageLocationPatchRequest.builder()
                .status(StorageLocationStatus.INACTIVE)
                .build();

        when(storageLocationRepository.findByIdAndSiteId(storageLocationId, siteId)).thenReturn(Optional.of(existing));
        when(locationInventoryInquiryClient.getOnHandQuantity(storageLocationId)).thenReturn(2);

        assertThatThrownBy(() -> storageLocationService.patchStorageLocation(siteId, storageLocationId, patch))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(rse.getReason()).contains("DESTINATION_REQUIRED");
                });
    }

    @Test
    @DisplayName("#39 - deactivateStorageLocation unknown id returns 404")
    void deactivateStorageLocation_notFound_throwsNotFound() {
        UUID siteId = UUID.randomUUID();
        UUID id = UUID.randomUUID();

        when(storageLocationRepository.findByIdAndSiteId(id, siteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storageLocationService.deactivateStorageLocation(siteId, id, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(rse.getReason()).contains("STORAGE_LOCATION_NOT_FOUND");
                });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal valid {@link StorageLocationRequest}.
     *
     * @param name     display name
     * @param barcode  barcode value
     * @param type     storage location type
     * @param parentId optional parent storage location id
     * @return populated request DTO
     */
    private StorageLocationRequest validRequest(String name, String barcode,
            StorageLocationType type, UUID parentId) {
        return StorageLocationRequest.builder()
                .name(name)
                .barcode(barcode)
                .type(type)
                .parentStorageLocationId(parentId)
                .build();
    }
}
