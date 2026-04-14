package com.positivity.location.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.BaseContractIntegrationTest;
import com.positivity.location.internal.dto.LocationRef;
import com.positivity.location.service.LocationRosterService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract behavior tests for the Location Roster reconciliation endpoint.
 *
 * <p>
 * Verifies HTTP contract for:
 * <ul>
 * <li>GET /v1/locations/roster — paginated location refs for sync
 * consumers</li>
 * <li>Optional filter: status, sinceUpdatedAt</li>
 * <li>400 on malformed sinceUpdatedAt timestamp</li>
 * </ul>
 *
 * Issue: CAP-214 #40
 */
@DisplayName("LocationRosterContractBehaviorIT")
class LocationRosterContractBehaviorIT extends BaseContractIntegrationTest {

    private static final UUID LOC_ID_1 = UUID.nameUUIDFromBytes("roster-loc-1-40".getBytes());
    private static final UUID LOC_ID_2 = UUID.nameUUIDFromBytes("roster-loc-2-40".getBytes());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocationRosterService locationRosterService;

    // -------------------------------------------------------------------------
    // GET /v1/locations/roster — no filter
    // -------------------------------------------------------------------------

    /**
     * No filter: returns 200 with a paginated list of location refs.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#40 - GET /locations/roster returns 200 with paginated list")
    void getRoster_noFilter_returns200WithPage() throws Exception {
        LocationRef ref1 = buildRef(LOC_ID_1, "Shop Alpha", "SHOP-A", "ACTIVE", null);
        LocationRef ref2 = buildRef(LOC_ID_2, "Shop Beta", "SHOP-B", "INACTIVE", "HR-002");
        Page<LocationRef> page = new PageImpl<>(List.of(ref1, ref2), PageRequest.of(0, 20), 2);

        when(locationRosterService.getRoster(isNull(), isNull(), any())).thenReturn(page);

        mockMvc.perform(withGatewayAuth(get("/v1/locations/roster")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(LOC_ID_1.toString()))
                .andExpect(jsonPath("$.content[0].name").value("Shop Alpha"))
                .andExpect(jsonPath("$.content[0].code").value("SHOP-A"))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"));
    }

    // -------------------------------------------------------------------------
    // GET /v1/locations/roster?status=ACTIVE
    // -------------------------------------------------------------------------

    /**
     * status=ACTIVE filter: returns 200 with only active location refs.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#40 - GET /locations/roster?status=ACTIVE returns 200 filtered")
    void getRoster_filterByStatus_returns200Filtered() throws Exception {
        LocationRef active = buildRef(LOC_ID_1, "Active Shop", "ACT-001", "ACTIVE", null);
        Page<LocationRef> page = new PageImpl<>(List.of(active), PageRequest.of(0, 20), 1);

        when(locationRosterService.getRoster(eq("ACTIVE"), isNull(), any())).thenReturn(page);

        mockMvc.perform(withGatewayAuth(get("/v1/locations/roster").param("status", "ACTIVE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"));
    }

    // -------------------------------------------------------------------------
    // GET /v1/locations/roster?sinceUpdatedAt=...
    // -------------------------------------------------------------------------

    /**
     * sinceUpdatedAt filter: returns 200 with location refs updated after the given
     * timestamp.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#40 - GET /locations/roster?sinceUpdatedAt=2026-01-01T00:00:00Z returns 200")
    void getRoster_filterBySinceUpdatedAt_returns200() throws Exception {
        LocationRef recent = buildRef(LOC_ID_1, "Recent Shop", "REC-001", "ACTIVE", "HR-101");
        Page<LocationRef> page = new PageImpl<>(List.of(recent), PageRequest.of(0, 20), 1);

        when(locationRosterService.getRoster(isNull(), any(Instant.class), any()))
                .thenReturn(page);

        mockMvc.perform(withGatewayAuth(get("/v1/locations/roster").param("sinceUpdatedAt", "2026-01-01T00:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].hrLocationId").value("HR-101"));
    }

    // -------------------------------------------------------------------------
    // GET /v1/locations/roster — invalid sinceUpdatedAt
    // -------------------------------------------------------------------------

    /**
     * Malformed sinceUpdatedAt query param: controller rejects with 400 Bad
     * Request.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    @DisplayName("#40 - GET /locations/roster with invalid sinceUpdatedAt returns 400")
    void getRoster_invalidDateFormat_returns400() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/locations/roster").param("sinceUpdatedAt", "not-a-date")))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private LocationRef buildRef(UUID id, String name, String code, String status, String hrLocationId) {
        return LocationRef.builder()
                .id(id)
                .name(name)
                .code(code)
                .status(status)
                .hrLocationId(hrLocationId)
                .updatedAt(Instant.parse("2026-02-01T12:00:00Z"))
                .build();
    }
}
