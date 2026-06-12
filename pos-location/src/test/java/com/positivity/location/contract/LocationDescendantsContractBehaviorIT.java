package com.positivity.location.contract;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.BaseContractIntegrationTest;
import com.positivity.location.internal.dto.LocationDescendantResponseDTO;
import com.positivity.location.service.LocationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * Contract behavior tests for the location descendants traversal endpoint
 * GET /v1/locations/{locationId}/descendants.
 *
 * Issue: CAP-214 #655
 */
@DisplayName("LocationDescendantsContractBehaviorIT")
class LocationDescendantsContractBehaviorIT extends BaseContractIntegrationTest {

    private static final UUID ROOT_ID = UUID.nameUUIDFromBytes("descendants-root-655".getBytes());
    private static final UUID CHILD_ID = UUID.nameUUIDFromBytes("descendants-child-655".getBytes());
    private static final UUID GRANDCHILD_ID = UUID.nameUUIDFromBytes("descendants-grandchild-655".getBytes());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocationService locationService;

    private static LocationDescendantResponseDTO descendant(UUID id, String name, UUID parentId, int depth) {
        return LocationDescendantResponseDTO.builder()
                .id(id)
                .name(name)
                .code("CODE-" + name)
                .status("ACTIVE")
                .parentId(parentId)
                .depth(depth)
                .build();
    }

    /**
     * Pins the flat descendant projection contract: id, name, code, status,
     * parentId, depth.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#655 - GET /descendants returns flat list with id, name, code, status, parentId, depth")
    void getDescendants_returnsFlatProjection() throws Exception {
        when(locationService.getDescendantsDto(eq(ROOT_ID), eq("PHYSICAL")))
                .thenReturn(List.of(
                        descendant(CHILD_ID, "Child", ROOT_ID, 1),
                        descendant(GRANDCHILD_ID, "Grandchild", CHILD_ID, 2)));

        mockMvc.perform(withGatewayAuth(
                        get("/v1/locations/{locationId}/descendants", ROOT_ID).queryParam("parentType", "PHYSICAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(CHILD_ID.toString()))
                .andExpect(jsonPath("$[0].name").value("Child"))
                .andExpect(jsonPath("$[0].code").value("CODE-Child"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].parentId").value(ROOT_ID.toString()))
                .andExpect(jsonPath("$[0].depth").value(1))
                .andExpect(jsonPath("$[1].id").value(GRANDCHILD_ID.toString()))
                .andExpect(jsonPath("$[1].parentId").value(CHILD_ID.toString()))
                .andExpect(jsonPath("$[1].depth").value(2));
    }

    /**
     * Omitting parentType defaults the traversal to PHYSICAL.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#655 - GET /descendants without parentType defaults to PHYSICAL")
    void getDescendants_missingParentType_defaultsToPhysical() throws Exception {
        when(locationService.getDescendantsDto(eq(ROOT_ID), eq("PHYSICAL"))).thenReturn(List.of());

        mockMvc.perform(withGatewayAuth(get("/v1/locations/{locationId}/descendants", ROOT_ID)))
                .andExpect(status().isOk());

        verify(locationService).getDescendantsDto(ROOT_ID, "PHYSICAL");
    }

    /**
     * No descendants yields 200 with an empty JSON array.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#655 - GET /descendants with no descendants returns 200 []")
    void getDescendants_noDescendants_returnsEmptyArray() throws Exception {
        when(locationService.getDescendantsDto(eq(ROOT_ID), eq("PHYSICAL"))).thenReturn(List.of());

        mockMvc.perform(withGatewayAuth(
                        get("/v1/locations/{locationId}/descendants", ROOT_ID).queryParam("parentType", "PHYSICAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Unknown root location yields 404.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#655 - GET /descendants for unknown location returns 404")
    void getDescendants_unknownLocation_returns404() throws Exception {
        when(locationService.getDescendantsDto(eq(ROOT_ID), eq("PHYSICAL")))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND"));

        mockMvc.perform(withGatewayAuth(
                        get("/v1/locations/{locationId}/descendants", ROOT_ID).queryParam("parentType", "PHYSICAL")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    /**
     * Unknown parentType value yields 400.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#655 - GET /descendants with invalid parentType returns 400")
    void getDescendants_invalidParentType_returns400() throws Exception {
        when(locationService.getDescendantsDto(eq(ROOT_ID), eq("NOT_A_TYPE")))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid parentType: NOT_A_TYPE"));

        mockMvc.perform(withGatewayAuth(
                        get("/v1/locations/{locationId}/descendants", ROOT_ID).queryParam("parentType", "NOT_A_TYPE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
