package com.positivity.location.contract;

import com.positivity.location.BaseContractIntegrationTest;
import com.positivity.location.internal.dto.StorageLocationResponse;
import com.positivity.location.internal.enums.StorageLocationType;
import com.positivity.location.service.StorageLocationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract behavior tests for StorageLocation topology endpoints.
 *
 * Verifies HTTP contract for create, list, get, update, and deactivate
 * operations
 * nested under /v1/locations/{locationId}/storage-locations.
 *
 * Issue: CAP-214 #39
 */
@DisplayName("StorageLocationContractBehaviorIT")
class StorageLocationContractBehaviorIT extends BaseContractIntegrationTest {

    private static final UUID SITE_ID = UUID.nameUUIDFromBytes("site-214-39".getBytes());
    private static final UUID STORAGE_ID = UUID.nameUUIDFromBytes("storage-214-39".getBytes());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StorageLocationService storageLocationService;

    // -------------------------------------------------------------------------
    // POST /v1/locations/{locationId}/storage-locations
    // -------------------------------------------------------------------------

    /**
     * Valid create request returns 201 Created with the new resource in the
     * response body.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#39 - POST /storage-locations with valid payload returns 201")
    void postStorageLocations_validPayload_returns201() throws Exception {
        UUID newId = UUID.randomUUID();
        when(storageLocationService.createStorageLocation(eq(SITE_ID), any()))
                .thenReturn(response(newId, "Bin-A1", StorageLocationType.BIN, "ACTIVE"));

        mockMvc.perform(withGatewayAuth(post("/v1/locations/{siteId}/storage-locations", SITE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreatePayload("Bin-A1", "BAR-001", "BIN"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(newId.toString()))
                .andExpect(jsonPath("$.name").value("Bin-A1"))
                .andExpect(jsonPath("$.type").value("BIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /**
     * Duplicate name within the same site must result in 409 Conflict.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#39 - POST /storage-locations with duplicate name returns 409")
    void postStorageLocations_duplicateName_returns409() throws Exception {
        when(storageLocationService.createStorageLocation(eq(SITE_ID), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "DUPLICATE_NAME"));

        mockMvc.perform(withGatewayAuth(post("/v1/locations/{siteId}/storage-locations", SITE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreatePayload("Shelf-DUP", "BAR-DUP1", "SHELF"))))
                .andExpect(status().isConflict());
    }

    /**
     * Duplicate barcode within the same site must result in 409 Conflict.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#39 - POST /storage-locations with duplicate barcode returns 409")
    void postStorageLocations_duplicateBarcode_returns409() throws Exception {
        when(storageLocationService.createStorageLocation(eq(SITE_ID), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "DUPLICATE_BARCODE"));

        mockMvc.perform(withGatewayAuth(post("/v1/locations/{siteId}/storage-locations", SITE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreatePayload("Floor-New", "BAR-DUP", "FLOOR"))))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // GET /v1/locations/{locationId}/storage-locations
    // -------------------------------------------------------------------------

    /**
     * Listing storage locations with no filters returns 200 with a paginated
     * result.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#39 - GET /storage-locations returns 200 with list")
    void getStorageLocations_returns200WithList() throws Exception {
        Page<StorageLocationResponse> page = new PageImpl<>(List.of(
                response(UUID.randomUUID(), "Bin-A", StorageLocationType.BIN, "ACTIVE"),
                response(UUID.randomUUID(), "Shelf-B", StorageLocationType.SHELF, "ACTIVE")));

        when(storageLocationService.listStorageLocations(eq(SITE_ID), any(), any()))
                .thenReturn(page);

        mockMvc.perform(withGatewayAuth(get("/v1/locations/{siteId}/storage-locations", SITE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    /**
     * Listing with a type filter delegates to the service and returns 200.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#39 - GET /storage-locations?type=BIN returns 200 filtered list")
    void getStorageLocations_filterByType_returns200() throws Exception {
        Page<StorageLocationResponse> page = new PageImpl<>(
                List.of(response(UUID.randomUUID(), "Bin-A", StorageLocationType.BIN, "ACTIVE")));

        when(storageLocationService.listStorageLocations(eq(SITE_ID), eq(StorageLocationType.BIN), any()))
                .thenReturn(page);

        mockMvc.perform(withGatewayAuth(get("/v1/locations/{siteId}/storage-locations", SITE_ID)
                .param("type", "BIN")))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // GET /v1/locations/{locationId}/storage-locations/{storageLocationId}
    // -------------------------------------------------------------------------

    /**
     * Fetching an existing StorageLocation by ID returns 200 with the resource
     * body.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#39 - GET /storage-locations/{id} when found returns 200")
    void getStorageLocation_whenFound_returns200() throws Exception {
        when(storageLocationService.getStorageLocation(SITE_ID, STORAGE_ID))
                .thenReturn(response(STORAGE_ID, "Cage-X", StorageLocationType.CAGE, "ACTIVE"));

        mockMvc.perform(withGatewayAuth(
                get("/v1/locations/{siteId}/storage-locations/{id}", SITE_ID, STORAGE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(STORAGE_ID.toString()))
                .andExpect(jsonPath("$.type").value("CAGE"));
    }

    /**
     * Fetching a StorageLocation that does not exist returns 404 Not Found.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#39 - GET /storage-locations/{id} when not found returns 404")
    void getStorageLocation_whenNotFound_returns404() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(storageLocationService.getStorageLocation(SITE_ID, missingId))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mockMvc.perform(withGatewayAuth(
                get("/v1/locations/{siteId}/storage-locations/{id}", SITE_ID, missingId)))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // PATCH /v1/locations/{locationId}/storage-locations/{storageLocationId}
    // -------------------------------------------------------------------------

    /**
     * A general PATCH (e.g., rename) returns 200 with the updated resource.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#39 - PATCH /storage-locations/{id} with valid update returns 200")
    void patchStorageLocation_validUpdate_returns200() throws Exception {
        when(storageLocationService.patchStorageLocation(eq(SITE_ID), eq(STORAGE_ID), any()))
                .thenReturn(response(STORAGE_ID, "Bin-A1-Renamed", StorageLocationType.BIN, "ACTIVE"));

        mockMvc.perform(withGatewayAuth(
                patch("/v1/locations/{siteId}/storage-locations/{id}", SITE_ID, STORAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Bin-A1-Renamed" }
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bin-A1-Renamed"));
    }

    /**
     * PATCH with status=INACTIVE (deactivate) and no inventory returns 200.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#39 - PATCH /storage-locations/{id} deactivate (no inventory) returns 200")
    void patchStorageLocation_deactivate_noInventory_returns200() throws Exception {
        when(storageLocationService.patchStorageLocation(eq(SITE_ID), eq(STORAGE_ID), any()))
                .thenReturn(response(STORAGE_ID, "Shelf-B2", StorageLocationType.SHELF, "INACTIVE"));

        mockMvc.perform(withGatewayAuth(
                patch("/v1/locations/{siteId}/storage-locations/{id}", SITE_ID, STORAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "INACTIVE" }
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    /**
     * PATCH deactivate with inventory but no destinationStorageLocationId returns
     * 422.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#39 - PATCH /storage-locations/{id} deactivate with inventory and no destination returns 422")
    void patchStorageLocation_deactivate_withInventory_noDestination_returns422() throws Exception {
        when(storageLocationService.patchStorageLocation(eq(SITE_ID), eq(STORAGE_ID), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "DESTINATION_REQUIRED"));

        mockMvc.perform(withGatewayAuth(
                patch("/v1/locations/{siteId}/storage-locations/{id}", SITE_ID, STORAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "INACTIVE" }
                                """)))
                .andExpect(status().isUnprocessableEntity());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal valid JSON create payload.
     *
     * @param name    display name
     * @param barcode barcode value
     * @param type    StorageLocationType string (e.g., "BIN")
     * @return JSON string
     */
    private String validCreatePayload(String name, String barcode, String type) {
        return """
                {
                  "name": "%s",
                  "barcode": "%s",
                  "type": "%s"
                }
                """.formatted(name, barcode, type);
    }

    /**
     * Builds a stub {@link StorageLocationResponse} for Mockito stubbing.
     *
     * @param id     resource id
     * @param name   display name
     * @param type   storage location type
     * @param status resource status
     * @return populated response DTO
     */
    private StorageLocationResponse response(UUID id, String name,
            StorageLocationType type, String status) {
        return StorageLocationResponse.builder()
                .id(id)
                .name(name)
                .type(type)
                .status(status)
                .build();
    }
}
