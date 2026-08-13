package com.positivity.vehicle.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shared.dto.VehicleResponse;
import com.positivity.vehicle.config.WebMvcTestSecurityConfig;
import com.positivity.vehicle.service.VehicleService;
import jakarta.persistence.EntityNotFoundException;
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
 * Web-slice tests for {@link VehicleRegistryController}.
 *
 * <p>
 * The controller no longer catches anything (#1269); the mapping now belongs to
 * {@code VehicleExceptionHandler} and is still the contract:
 * {@link EntityNotFoundException} becomes 404 and {@link IllegalArgumentException}
 * becomes 400. Those two arrive from the same service methods, so collapsing them
 * would turn "you asked for something that does not exist" into "your request was
 * malformed", or the reverse. The pair is pinned on both update and delete.
 *
 * <p>
 * The envelope itself is pinned too. Every error leaving this module must carry
 * {@code ApiError} (ADR-0017) — the bare statuses it used to return gave a caller
 * no {@code code} to branch on and no {@code correlationId} to quote.
 */
@WebMvcTest(VehicleRegistryController.class)
@Import(WebMvcTestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
@DisplayName("VehicleRegistryController — web slice")
class VehicleRegistryControllerWebMvcTest {

    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final String VIN = "1HGCM82633A004352";
    private static final String PATH = "/v1/vehicle-registry";
    private static final String AUTH = "Authorization";
    private static final String BEARER = "Bearer test";
    private static final String CREATE_BODY = "{\"accountId\":\"" + ACCOUNT_ID + "\",\"vin\":\"" + VIN + "\"}";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    VehicleService vehicleService;

    private static VehicleResponse vehicle() {
        return VehicleResponse.builder()
                .vehicleId(VEHICLE_ID)
                .accountId(ACCOUNT_ID)
                .vin(VIN)
                .vinNormalized(VIN)
                .unitNumber("UNIT-001")
                .description("2024 Honda Accord")
                .build();
    }

    @Test
    @DisplayName("POST returns 201 with the created vehicle")
    void createReturnsCreated() throws Exception {
        when(vehicleService.createVehicle(any())).thenReturn(vehicle());

        // 201, not 200: this endpoint creates a registry record, and the status is what tells
        // a client the VIN was not already present.
        mockMvc.perform(post(PATH)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vehicleId").value(VEHICLE_ID.toString()))
                .andExpect(jsonPath("$.vin").value(VIN));
    }

    @Test
    @DisplayName("POST rejects a body with no VIN before reaching the service")
    void createWithoutVinIsRejected() throws Exception {
        mockMvc.perform(post(PATH)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + ACCOUNT_ID + "\"}"))
                .andExpect(status().isBadRequest());

        // Bean validation must fail closed here — a registry record with no VIN has no way to
        // be matched to a real vehicle later.
        verify(vehicleService, never()).createVehicle(any());
    }

    @Test
    @DisplayName("GET by id returns 404 for an unknown vehicle")
    void getUnknownIdIsNotFound() throws Exception {
        when(vehicleService.getVehicle(VEHICLE_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get(PATH + "/" + VEHICLE_ID).header(AUTH, BEARER)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET by VIN returns 404 for an unknown VIN")
    void getUnknownVinIsNotFound() throws Exception {
        when(vehicleService.getVehicleByVin(VIN)).thenReturn(Optional.empty());

        mockMvc.perform(get(PATH + "/vin/" + VIN).header(AUTH, BEARER)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT maps a missing vehicle to 404 and a rejected update to 400")
    void updateDistinguishesNotFoundFromBadRequest() throws Exception {
        when(vehicleService.updateVehicle(eq(VEHICLE_ID), any()))
                .thenThrow(new EntityNotFoundException("no such vehicle"))
                .thenThrow(new IllegalArgumentException("unit number too long"));

        // Two consecutive calls, two different exceptions from the same method. Collapsing the
        // advice's two handlers would make one of these silently report the other.
        mockMvc.perform(put(PATH + "/" + VEHICLE_ID)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitNumber\":\"UNIT-002\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(put(PATH + "/" + VEHICLE_ID)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitNumber\":\"UNIT-002\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT returns 200 with the updated vehicle")
    void updateReturnsOk() throws Exception {
        when(vehicleService.updateVehicle(eq(VEHICLE_ID), any())).thenReturn(vehicle());

        mockMvc.perform(put(PATH + "/" + VEHICLE_ID)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitNumber\":\"UNIT-002\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleId").value(VEHICLE_ID.toString()));
    }

    @Test
    @DisplayName("DELETE returns 204, and 404 when the vehicle is already gone")
    void deleteDistinguishesSuccessFromMissing() throws Exception {
        mockMvc.perform(delete(PATH + "/" + VEHICLE_ID).header(AUTH, BEARER)).andExpect(status().isNoContent());

        doThrow(new EntityNotFoundException("no such vehicle"))
                .when(vehicleService)
                .deleteVehicle(VEHICLE_ID);

        mockMvc.perform(delete(PATH + "/" + VEHICLE_ID).header(AUTH, BEARER)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a VIN of the wrong length is a 400 naming the field, not a server error")
    void shortVinOnPathIsBadRequest() throws Exception {
        // The class is @Validated and the path variable carries @Size(min = 17, max = 17), so a
        // short VIN is rejected as a ConstraintViolationException. Until #1269 the module had no
        // advice to translate that and it escaped as a 500 — a malformed request reported as a
        // server fault, which also made a client looping on a bad VIN look like an outage. The
        // field name is asserted because the violation's property path is method-scoped
        // ("getVehicleByVin.vin") and only the leaf names something the caller actually sent.
        mockMvc.perform(get(PATH + "/vin/TOOSHORT").header(AUTH, BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("vin"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("size must be between 17 and 17"));

        verifyNoInteractions(vehicleService);
    }

    @Test
    @DisplayName("POST turns a rejected create into 400 carrying the reason, not 500")
    void createRejectionCarriesApiError() throws Exception {
        when(vehicleService.createVehicle(any())).thenThrow(new IllegalArgumentException("duplicate VIN"));

        // The status alone was never the useful part: before #1269 the 400 had an empty body, so
        // the reason the service rejected the request was written to the log and discarded.
        mockMvc.perform(post(PATH)
                        .header(AUTH, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("duplicate VIN"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("a correlation id supplied by the caller is echoed back in the envelope")
    void correlationIdFromRequestIsPreserved() throws Exception {
        when(vehicleService.getVehicle(VEHICLE_ID)).thenReturn(Optional.empty());
        doThrow(new EntityNotFoundException("no such vehicle"))
                .when(vehicleService)
                .deleteVehicle(VEHICLE_ID);

        // A support ticket quotes the correlation id the caller already has; minting a fresh one
        // on the error path would break the trace at exactly the point someone needs it.
        mockMvc.perform(delete(PATH + "/" + VEHICLE_ID).header(AUTH, BEARER).header("X-Correlation-Id", "trace-1269"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", "trace-1269"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value("trace-1269"));
    }

    @Test
    @DisplayName("rejects an unauthenticated request")
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get(PATH + "/" + VEHICLE_ID)).andExpect(status().isUnauthorized());

        verify(vehicleService, never()).getVehicle(any());
    }
}
