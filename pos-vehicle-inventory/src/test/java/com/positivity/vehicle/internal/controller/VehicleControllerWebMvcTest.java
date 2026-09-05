package com.positivity.vehicle.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.vehicle.config.WebMvcTestSecurityConfig;
import com.positivity.vehicle.internal.dto.VehicleLegacyResponse;
import com.positivity.vehicle.internal.exception.VehicleValidationException;
import com.positivity.vehicle.internal.service.VehicleLegacyService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@link VehicleController}, the legacy
 * {@code /v1/vehicles-legacy} surface.
 *
 * <p>
 * Every endpoint here has an id form and a VIN form, and the two report absence
 * through different mechanisms: the read and update paths return an
 * {@link Optional} that {@code ResponseEntity.of} turns into a 404, while the
 * delete paths return a boolean that the controller checks by hand. Both must
 * produce a 404 for a vehicle that is not there, and the boolean form is the one
 * that silently becomes a 204 if the check is dropped — telling a caller their
 * delete succeeded when nothing was deleted. That is what these tests are for;
 * the happy paths are here mainly to make the absence cases meaningful.
 */
@WebMvcTest(VehicleController.class)
@Import(WebMvcTestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
@DisplayName("VehicleController (legacy) — web slice")
class VehicleControllerWebMvcTest {

    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final String VIN = "1HGCM82633A004352";
    private static final String PATH = "/v1/vehicles-legacy";
    private static final String AUTH = "Authorization";
    private static final String BEARER = "Bearer test";
    private static final String BODY =
            "{\"make\":\"Honda\",\"model\":\"Accord\",\"year\":2024,\"vin\":\"" + VIN + "\"}";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    VehicleLegacyService vehicleLegacyService;

    private static VehicleLegacyResponse vehicle() {
        return VehicleLegacyResponse.builder()
                .id(VEHICLE_ID)
                .make("Honda")
                .model("Accord")
                .year(2024)
                .vin(VIN)
                .build();
    }

    @Test
    @DisplayName("POST returns the created vehicle")
    void createReturnsVehicle() throws Exception {
        when(vehicleLegacyService.createVehicle(any())).thenReturn(vehicle());

        mockMvc.perform(post(PATH)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vin").value(VIN));
    }

    @Test
    @DisplayName("POST turns a rejected create into 400, not 500")
    void createRejectionIsBadRequest() throws Exception {
        when(vehicleLegacyService.createVehicle(any())).thenThrow(new VehicleValidationException("bad VIN"));

        mockMvc.perform(post(PATH)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET by id returns 404 for an unknown vehicle")
    void getUnknownIdIsNotFound() throws Exception {
        when(vehicleLegacyService.getVehicle(VEHICLE_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get(PATH + "/" + VEHICLE_ID).header(AUTH, BEARER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("GET by VIN returns 404 for an unknown VIN")
    void getUnknownVinIsNotFound() throws Exception {
        when(vehicleLegacyService.getVehicleByVin(VIN)).thenReturn(Optional.empty());

        mockMvc.perform(get(PATH + "/vin/" + VIN).header(AUTH, BEARER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("GET returns the full list")
    void getAllReturnsList() throws Exception {
        when(vehicleLegacyService.getAllVehicles()).thenReturn(List.of(vehicle()));

        mockMvc.perform(get(PATH).header(AUTH, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(VEHICLE_ID.toString()));
    }

    @Test
    @DisplayName("PUT by id returns 404 when the vehicle does not exist")
    void updateUnknownIdIsNotFound() throws Exception {
        when(vehicleLegacyService.updateVehicle(eq(VEHICLE_ID), any())).thenReturn(Optional.empty());

        // ResponseEntity.of collapses the empty Optional to a 404 — an update must not report
        // success for a row it never touched.
        mockMvc.perform(put(PATH + "/" + VEHICLE_ID)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("PUT by VIN returns 404 when the VIN is unknown")
    void updateUnknownVinIsNotFound() throws Exception {
        when(vehicleLegacyService.updateVehicleByVin(eq(VIN), any())).thenReturn(Optional.empty());

        mockMvc.perform(put(PATH + "/vin/" + VIN)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("DELETE by id reports 204 only when something was actually deleted")
    void deleteByIdDistinguishesDeletedFromMissing() throws Exception {
        when(vehicleLegacyService.deleteVehicle(VEHICLE_ID)).thenReturn(true).thenReturn(false);

        mockMvc.perform(delete(PATH + "/" + VEHICLE_ID).header(AUTH, BEARER)).andExpect(status().isNoContent());

        // The false case is the one that matters: without the explicit check this would also be
        // a 204, telling the caller a vehicle was removed when none was.
        mockMvc.perform(delete(PATH + "/" + VEHICLE_ID).header(AUTH, BEARER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("DELETE by VIN reports 204 only when something was actually deleted")
    void deleteByVinDistinguishesDeletedFromMissing() throws Exception {
        when(vehicleLegacyService.deleteVehicleByVin(VIN)).thenReturn(true).thenReturn(false);

        mockMvc.perform(delete(PATH + "/vin/" + VIN).header(AUTH, BEARER)).andExpect(status().isNoContent());

        mockMvc.perform(delete(PATH + "/vin/" + VIN).header(AUTH, BEARER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("POST /vin returns the created vehicle")
    void createByVinReturnsVehicle() throws Exception {
        when(vehicleLegacyService.createVehicleByVin(any())).thenReturn(vehicle());

        mockMvc.perform(post(PATH + "/vin")
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vin").value(VIN));
    }

    @Test
    @DisplayName("rejects an unauthenticated request")
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get(PATH).header("X-Authorities", "vehicle-inventory:registry:create"))
                .andExpect(status().isUnauthorized());
    }
}
