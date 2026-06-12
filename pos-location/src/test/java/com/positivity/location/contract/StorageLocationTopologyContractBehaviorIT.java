package com.positivity.location.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.BaseContractIntegrationTest;
import com.positivity.location.internal.dto.StorageLocationResponse;
import com.positivity.location.internal.dto.StorageLocationTopologyResponse;
import com.positivity.location.internal.enums.StorageLocationType;
import com.positivity.location.service.StorageLocationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract behavior tests pinning the storage-location topology fields that
 * pos-inventory rollup consumers rely on: id, name, type, status,
 * parentStorageLocationId (FR-3).
 *
 * Issue: CAP-214 #655
 */
@DisplayName("StorageLocationTopologyContractBehaviorIT")
class StorageLocationTopologyContractBehaviorIT extends BaseContractIntegrationTest {

    private static final UUID SITE_ID = UUID.nameUUIDFromBytes("topology-site-655".getBytes());
    private static final UUID FLOOR_ID = UUID.nameUUIDFromBytes("topology-floor-655".getBytes());
    private static final UUID SHELF_ID = UUID.nameUUIDFromBytes("topology-shelf-655".getBytes());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StorageLocationService storageLocationService;

    private static StorageLocationTopologyResponse topologyEntry(
            UUID id, String name, StorageLocationType type, String status, UUID parentId) {
        return StorageLocationTopologyResponse.builder()
                .id(id)
                .name(name)
                .type(type)
                .status(status)
                .parentStorageLocationId(parentId)
                .build();
    }

    /**
     * Pins the flat unpaginated topology contract including non-ACTIVE
     * statuses.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#655 - GET /storage-locations/topology returns flat list with pinned fields, all statuses")
    void getTopology_returnsPinnedFieldsForAllStatuses() throws Exception {
        when(storageLocationService.listStorageLocationTopology(eq(SITE_ID)))
                .thenReturn(List.of(
                        topologyEntry(FLOOR_ID, "Floor-1", StorageLocationType.FLOOR, "ACTIVE", null),
                        topologyEntry(SHELF_ID, "Shelf-1", StorageLocationType.SHELF, "INACTIVE", FLOOR_ID)));

        mockMvc.perform(withGatewayAuth(get("/v1/locations/{siteId}/storage-locations/topology", SITE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(FLOOR_ID.toString()))
                .andExpect(jsonPath("$[0].name").value("Floor-1"))
                .andExpect(jsonPath("$[0].type").value("FLOOR"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].parentStorageLocationId").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(SHELF_ID.toString()))
                .andExpect(jsonPath("$[1].name").value("Shelf-1"))
                .andExpect(jsonPath("$[1].type").value("SHELF"))
                .andExpect(jsonPath("$[1].status").value("INACTIVE"))
                .andExpect(jsonPath("$[1].parentStorageLocationId").value(FLOOR_ID.toString()));
    }

    /**
     * Empty topology yields 200 with an empty JSON array.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#655 - GET /storage-locations/topology for empty site returns 200 []")
    void getTopology_emptySite_returnsEmptyArray() throws Exception {
        when(storageLocationService.listStorageLocationTopology(eq(SITE_ID))).thenReturn(List.of());

        mockMvc.perform(withGatewayAuth(get("/v1/locations/{siteId}/storage-locations/topology", SITE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Pins the paginated listing contract fields a pos-inventory consumer
     * relies on when reading the existing list endpoint: id, name, type,
     * status, parentStorageLocationId. Also pins that non-ACTIVE statuses are
     * returned when no status filter is supplied.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#655 - GET /storage-locations list pins consumer fields and includes non-ACTIVE statuses")
    void listStorageLocations_pinsConsumerContractFields() throws Exception {
        StorageLocationResponse inactiveShelf = StorageLocationResponse.builder()
                .id(SHELF_ID)
                .name("Shelf-1")
                .type(StorageLocationType.SHELF)
                .status("INACTIVE")
                .siteId(SITE_ID)
                .parentStorageLocationId(FLOOR_ID)
                .build();
        when(storageLocationService.listStorageLocations(eq(SITE_ID), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(inactiveShelf)));

        mockMvc.perform(withGatewayAuth(get("/v1/locations/{siteId}/storage-locations", SITE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(SHELF_ID.toString()))
                .andExpect(jsonPath("$.content[0].name").value("Shelf-1"))
                .andExpect(jsonPath("$.content[0].type").value("SHELF"))
                .andExpect(jsonPath("$.content[0].status").value("INACTIVE"))
                .andExpect(jsonPath("$.content[0].parentStorageLocationId").value(FLOOR_ID.toString()));
    }
}
