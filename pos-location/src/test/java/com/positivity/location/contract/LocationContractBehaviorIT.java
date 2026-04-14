package com.positivity.location.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.BaseContractIntegrationTest;
import com.positivity.location.internal.dto.LocationRequestDTO;
import com.positivity.location.internal.dto.LocationResponseDTO;
import com.positivity.location.internal.dto.LocationTypeDTO;
import com.positivity.location.service.LocationService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * Contract behavior tests for shop location create/maintain API.
 *
 * Issue: CAP-136 #78
 */
@DisplayName("LocationContractBehaviorIT")
class LocationContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocationService locationService;

    @Test
    void postLocations_valid_returns201() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(locationService.createLocation(any(LocationRequestDTO.class)))
                .thenReturn(response(id, "North Shop", "LOC-900"));

        mockMvc.perform(withGatewayAuth(post("/v1/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreatePayload("North Shop", "LOC-900"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("North Shop"));
    }

    @Test
    void postLocations_invalidTimezone_returns422() throws Exception {
        when(locationService.createLocation(any(LocationRequestDTO.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_TIMEZONE"));

        mockMvc.perform(withGatewayAuth(post("/v1/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreatePayload("Timezone Shop", "LOC-901"))))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void postLocations_duplicateName_returns409() throws Exception {
        when(locationService.createLocation(any(LocationRequestDTO.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "LOCATION_NAME_TAKEN"));

        mockMvc.perform(withGatewayAuth(post("/v1/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreatePayload("Main Shop", "LOC-902"))))
                .andExpect(status().isConflict());
    }

    @Test
    void getLocations_returns200() throws Exception {
        when(locationService.getAllLocationsDto())
                .thenReturn(List.of(
                        response(UUID.fromString("00000000-0000-0000-0000-000000000001"), "A", "LOC-A"),
                        response(UUID.fromString("00000000-0000-0000-0000-000000000001"), "B", "LOC-B")));

        mockMvc.perform(withGatewayAuth(get("/v1/locations")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getLocationById_found_returns200() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(locationService.getLocationByIdDto(id)).thenReturn(Optional.of(response(id, "Found", "LOC-903")));

        mockMvc.perform(withGatewayAuth(get("/v1/locations/{id}", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void getLocationById_notFound_returns404() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(locationService.getLocationByIdDto(id)).thenReturn(Optional.empty());

        mockMvc.perform(withGatewayAuth(get("/v1/locations/{id}", id))).andExpect(status().isNotFound());
    }

    @Test
    void putLocation_valid_returns200() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(locationService.updateLocation(eq(id), any(LocationRequestDTO.class)))
                .thenReturn(Optional.of(response(id, "Updated", "LOC-904")));

        mockMvc.perform(withGatewayAuth(put("/v1/locations/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreatePayload("Updated", "LOC-904"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"));
    }

    @Test
    void patchLocation_deactivate_returns200() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(withGatewayAuth(patch("/v1/locations/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}")))
                .andExpect(status().isOk());
    }

    private static LocationResponseDTO response(UUID id, String name, String code) {
        return LocationResponseDTO.builder()
                .id(id)
                .name(name)
                .code(code)
                .active(true)
                .type(LocationTypeDTO.builder().name("Shop").build())
                .build();
    }

    private static String validCreatePayload(String name, String code) {
        return """
                                {
                                  "name": "%s",
                                  "code": "%s",
                                  "type": { "name": "Shop" }
                                }
                                """.formatted(name, code);
    }
}
