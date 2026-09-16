package com.positivity.vehicle.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shared.dto.UpdateVehicleRequest;
import com.positivity.shared.dto.VehicleResponse;
import com.positivity.vehicle.config.WebMvcTestSecurityConfig;
import com.positivity.vehicle.internal.service.VehicleFactReplayService;
import com.positivity.vehicle.internal.service.VehicleService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the request-validation edge of {@code PUT /v1/vehicle-registry/{vehicleId}}: the "at least
 * one field" guard must count every mutable field the service applies, {@code gvwrClass} (CAP-327)
 * included, or an operator setting only the class is refused before the update runs.
 */
@WebMvcTest(VehicleRegistryController.class)
@Import(WebMvcTestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100"})
class VehicleRegistryControllerUpdateTest {

    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    VehicleService vehicleService;

    @MockitoBean
    VehicleFactReplayService vehicleFactReplayService;

    @Test
    void updateVehicle_gvwrClassAlone_isAValidPartialUpdate() throws Exception {
        VehicleResponse response =
                VehicleResponse.builder().vehicleId(VEHICLE_ID).gvwrClass(7).build();
        when(vehicleService.updateVehicle(eq(VEHICLE_ID), any())).thenReturn(response);

        mockMvc.perform(put("/v1/vehicle-registry/{vehicleId}", VEHICLE_ID)
                        .header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gvwrClass\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gvwrClass").value(7));

        ArgumentCaptor<UpdateVehicleRequest> sent = ArgumentCaptor.forClass(UpdateVehicleRequest.class);
        verify(vehicleService).updateVehicle(eq(VEHICLE_ID), sent.capture());
        assertThat(sent.getValue().getGvwrClass()).isEqualTo(7);
    }

    @Test
    void updateVehicle_emptyBody_isRejectedBeforeTheService() throws Exception {
        mockMvc.perform(put("/v1/vehicle-registry/{vehicleId}", VEHICLE_ID)
                        .header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(vehicleService, never()).updateVehicle(any(), any());
    }
}
