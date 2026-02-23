package com.positivity.location.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.BaseContractIntegrationTest;
import com.positivity.location.internal.controller.BayController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Contract RED tests for location bay endpoints required by Story #77.
 *
 * Issue: CAP-136 #77
 */
class BayContractBehaviorIT extends BaseContractIntegrationTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(new BayController()).build();
    }

    @Test
    @DisplayName("#77 - POST /v1/locations/{locationId}/bays with valid payload returns 201")
    void createBay_withValidPayload_returnsCreated() throws Exception {
        String payload = """
                {
                  "name": "Lane-A1",
                  "bayType": "TIRE_SERVICE",
                  "status": "ACTIVE",
                  "capacity": {
                    "maxConcurrentVehicles": 2
                  },
                  "serviceCapabilityIds": ["cap-tire-rotation"],
                  "skillRequirementIds": ["skill-ase-tire"]
                }
                """;

        mockMvc.perform(post("/v1/locations/{locationId}/bays", 101)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("#77 - POST /v1/locations/{locationId}/bays with missing capacity returns 400")
    void createBay_missingCapacity_returnsBadRequest() throws Exception {
        String payload = """
                {
                  "name": "Lane-A2",
                  "bayType": "GENERAL_SERVICE",
                  "status": "ACTIVE"
                }
                """;

        mockMvc.perform(post("/v1/locations/{locationId}/bays", 101)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("#77 - POST /v1/locations/{locationId}/bays duplicate name returns 409")
    void createBay_duplicateName_returnsConflict() throws Exception {
        String payload = """
                {
                  "name": "Lane-DUP",
                  "bayType": "INSPECTION",
                  "status": "ACTIVE",
                  "capacity": {
                    "maxConcurrentVehicles": 1
                  }
                }
                """;

        mockMvc.perform(post("/v1/locations/{locationId}/bays", 101)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));

        mockMvc.perform(post("/v1/locations/{locationId}/bays", 101)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("#77 - GET /v1/locations/{locationId}/bays list and filter returns 200")
    void listBays_withFilters_returnsOk() throws Exception {
        mockMvc.perform(get("/v1/locations/{locationId}/bays", 101)
                .param("status", "ACTIVE")
                .param("bayType", "TIRE_SERVICE"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("#77 - GET /v1/locations/{locationId}/bays/{bayId} returns 200 when found")
    void getBay_whenFound_returnsOk() throws Exception {
        mockMvc.perform(get("/v1/locations/{locationId}/bays/{bayId}", 101, 201))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("#77 - GET /v1/locations/{locationId}/bays/{bayId} returns 404 when not found")
    void getBay_whenNotFound_returnsNotFound() throws Exception {
        mockMvc.perform(get("/v1/locations/{locationId}/bays/{bayId}", 101, 999999))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("#77 - PATCH /v1/locations/{locationId}/bays/{bayId} returns 200")
    void patchBay_returnsOk() throws Exception {
        String payload = """
                {
                  "status": "OUT_OF_SERVICE",
                  "name": "Lane-A1-Maintenance"
                }
                """;

        mockMvc.perform(patch("/v1/locations/{locationId}/bays/{bayId}", 101, 201)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk());
    }
}
