package com.positivity.location.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.BaseContractIntegrationTest;
import com.positivity.location.internal.entity.BayEntity;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.ServiceLocationCapabilityEntity;
import com.positivity.location.internal.repository.BayRepository;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.ServiceLocationCapabilityRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract RED tests for location bay endpoints required by Story #77.
 *
 * Issue: CAP-136 #77
 */
class BayContractBehaviorIT extends BaseContractIntegrationTest {

    private UUID locationId;
    private UUID existingBayId;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private BayRepository bayRepository;

    @Autowired
    private ServiceLocationCapabilityRepository serviceLocationCapabilityRepository;

    @BeforeEach
    void setUpData() {
        bayRepository.deleteAll();
        locationRepository.deleteAll();
        serviceLocationCapabilityRepository.deleteAll();

        Location location = Location.builder()
                .name("Main Service Center")
                .code("LOC-101")
                .status("ACTIVE")
                .active(true)
                .build();
        location = locationRepository.save(location);
        locationId = location.getId();

        BayEntity existingBay = BayEntity.builder()
                .location(location)
                .name("Lane-Seed")
                .bayType("GENERAL_SERVICE")
                .status("ACTIVE")
                .maxConcurrentVehicles(1)
                .build();
        existingBay = bayRepository.save(existingBay);
        existingBayId = existingBay.getId();

        serviceLocationCapabilityRepository.save(ServiceLocationCapabilityEntity.builder()
                .code("CAP-TIRE-ROTATION")
                .name("Tire Rotation")
                .active(true)
                .build());
    }

    @AfterEach
    void cleanUp() {
        bayRepository.deleteAll();
        locationRepository.deleteAll();
        serviceLocationCapabilityRepository.deleteAll();
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

        mockMvc.perform(withGatewayAuth(post("/v1/locations/{locationId}/bays", locationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
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

        mockMvc.perform(withGatewayAuth(post("/v1/locations/{locationId}/bays", locationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
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

        mockMvc.perform(withGatewayAuth(post("/v1/locations/{locationId}/bays", locationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)));

        mockMvc.perform(withGatewayAuth(post("/v1/locations/{locationId}/bays", locationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("#77 - GET /v1/locations/{locationId}/bays list and filter returns 200")
    void listBays_withFilters_returnsOk() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/locations/{locationId}/bays", locationId)
                        .param("status", "ACTIVE")
                        .param("bayType", "TIRE_SERVICE")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("#77 - GET /v1/locations/{locationId}/bays/{bayId} returns 200 when found")
    void getBay_whenFound_returnsOk() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/locations/{locationId}/bays/{bayId}", locationId, existingBayId)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("#77 - GET /v1/locations/{locationId}/bays/{bayId} returns 404 when not found")
    void getBay_whenNotFound_returnsNotFound() throws Exception {
        mockMvc.perform(withGatewayAuth(get(
                        "/v1/locations/{locationId}/bays/{bayId}",
                        locationId,
                        UUID.fromString("00000000-0000-0000-0000-000000000001"))))
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

        mockMvc.perform(withGatewayAuth(patch("/v1/locations/{locationId}/bays/{bayId}", locationId, existingBayId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isOk());
    }
}
