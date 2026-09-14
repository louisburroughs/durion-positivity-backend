package com.positivity.workorder.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.internal.controller.ServicePositionController;
import com.positivity.workorder.internal.dto.AssignServicePositionRequest;
import com.positivity.workorder.internal.dto.ServicePositionResponse;
import com.positivity.workorder.internal.enums.ResourceType;
import com.positivity.workorder.internal.exception.ServicePositionInvalidException;
import com.positivity.workorder.internal.exception.ServicePositionOccupiedException;
import com.positivity.workorder.internal.exception.WorkorderClosedException;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.service.ServicePositionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The service-position endpoints and the status codes their failures map to (#1983, #1984). */
@WebMvcTest(ServicePositionController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class ServicePositionControllerTest {

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000001983");
    private static final UUID SITE_ID = UUID.fromString("00000000-0000-0000-0000-000000001984");
    private static final UUID BAY_ID = UUID.fromString("00000000-0000-0000-0000-000000001985");
    private static final UUID OCCUPANT_ID = UUID.fromString("00000000-0000-0000-0000-000000001986");

    private static final String URL = "/v1/workorders/{id}/position";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ServicePositionService servicePositionService;

    private static ServicePositionResponse response(ResourceType resourceType, UUID resourceId) {
        return ServicePositionResponse.builder()
                .workorderId(WORKORDER_ID)
                .locationId(SITE_ID)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .workorderStatus("ASSIGNED")
                .history(List.of())
                .build();
    }

    @Test
    @DisplayName("#1983: PUT places the workorder on a bay and echoes the new position")
    void assignsAPosition() throws Exception {
        when(servicePositionService.assignPosition(eq(WORKORDER_ID), any(), any()))
                .thenReturn(response(ResourceType.BAY, BAY_ID));

        AssignServicePositionRequest request = AssignServicePositionRequest.builder()
                .resourceType(ResourceType.BAY)
                .resourceId(BAY_ID)
                .reason("Alignment rack")
                .build();

        mockMvc.perform(put(URL, WORKORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("BAY"))
                .andExpect(jsonPath("$.resourceId").value(BAY_ID.toString()));

        ArgumentCaptor<AssignServicePositionRequest> captor =
                ArgumentCaptor.forClass(AssignServicePositionRequest.class);
        verify(servicePositionService).assignPosition(eq(WORKORDER_ID), captor.capture(), any());
        assertThat(captor.getValue().getResourceType()).isEqualTo(ResourceType.BAY);
        assertThat(captor.getValue().getReason()).isEqualTo("Alignment rack");
    }

    @Test
    @DisplayName("#1984: a HOLD assignment needs no resourceId — the server fills in the site")
    void parksWithoutAResourceId() throws Exception {
        when(servicePositionService.assignPosition(eq(WORKORDER_ID), any(), any()))
                .thenReturn(response(ResourceType.HOLD, SITE_ID));

        mockMvc.perform(put(URL, WORKORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"HOLD\",\"reason\":\"Awaiting parts\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("HOLD"))
                .andExpect(jsonPath("$.resourceId").value(SITE_ID.toString()));
    }

    @Test
    @DisplayName("a body with no resourceType is rejected before the service is reached")
    void resourceTypeIsRequired() throws Exception {
        mockMvc.perform(put(URL, WORKORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\":\"" + BAY_ID + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("#1984: an occupied position answers 409 RESOURCE_OCCUPIED, naming the occupant")
    void occupiedPositionIsAConflict() throws Exception {
        doThrow(new ServicePositionOccupiedException("BAY " + BAY_ID + " already holds open workorder", OCCUPANT_ID))
                .when(servicePositionService)
                .assignPosition(eq(WORKORDER_ID), any(), any());

        mockMvc.perform(put(URL, WORKORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"BAY\",\"resourceId\":\"" + BAY_ID + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_OCCUPIED"))
                // The occupying workorder travels as referenceId so a dispatch board can link to it
                // rather than parsing the message.
                .andExpect(jsonPath("$.referenceId").value(OCCUPANT_ID.toString()));
    }

    @Test
    @DisplayName("#1983: an unknown or foreign position answers 422 SERVICE_POSITION_INVALID")
    void invalidPositionIsUnprocessable() throws Exception {
        doThrow(new ServicePositionInvalidException("Unknown bay " + BAY_ID))
                .when(servicePositionService)
                .assignPosition(eq(WORKORDER_ID), any(), any());

        mockMvc.perform(put(URL, WORKORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"BAY\",\"resourceId\":\"" + BAY_ID + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SERVICE_POSITION_INVALID"));
    }

    @Test
    @DisplayName("#1983: a closed workorder answers 409 with the stable WORKORDER_CLOSED code")
    void closedWorkorderIsAConflict() throws Exception {
        doThrow(new WorkorderClosedException(WORKORDER_ID, "COMPLETED"))
                .when(servicePositionService)
                .assignPosition(eq(WORKORDER_ID), any(), any());

        mockMvc.perform(put(URL, WORKORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"BAY\",\"resourceId\":\"" + BAY_ID + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKORDER_CLOSED"));
    }

    @Test
    @DisplayName("#1983: DELETE releases the position and passes the reason through")
    void releasesAPosition() throws Exception {
        when(servicePositionService.releasePosition(eq(WORKORDER_ID), any(), eq("Moved to the lot")))
                .thenReturn(response(null, null));

        mockMvc.perform(delete(URL, WORKORDER_ID).param("reason", "Moved to the lot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId").doesNotExist());

        verify(servicePositionService).releasePosition(eq(WORKORDER_ID), any(), eq("Moved to the lot"));
    }

    @Test
    @DisplayName("the release reason is optional")
    void releaseWithoutAReason() throws Exception {
        when(servicePositionService.releasePosition(eq(WORKORDER_ID), any(), isNull()))
                .thenReturn(response(null, null));

        mockMvc.perform(delete(URL, WORKORDER_ID)).andExpect(status().isOk());

        verify(servicePositionService).releasePosition(eq(WORKORDER_ID), any(), isNull());
    }

    @Test
    @DisplayName("#1983: GET returns the position and the technician together")
    void readsPositionAndTechnician() throws Exception {
        UUID technicianId = UUID.fromString("00000000-0000-0000-0000-000000001987");
        when(servicePositionService.getPosition(WORKORDER_ID))
                .thenReturn(ServicePositionResponse.builder()
                        .workorderId(WORKORDER_ID)
                        .locationId(SITE_ID)
                        .resourceType(ResourceType.BAY)
                        .resourceId(BAY_ID)
                        .technicianId(technicianId)
                        .workorderStatus("WORK_IN_PROGRESS")
                        .history(List.of())
                        .build());

        mockMvc.perform(get(URL, WORKORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId").value(BAY_ID.toString()))
                .andExpect(jsonPath("$.technicianId").value(technicianId.toString()))
                .andExpect(jsonPath("$.workorderStatus").value("WORK_IN_PROGRESS"));
    }

    @Test
    @DisplayName("an unknown workorder is a 404 on every verb")
    void unknownWorkorderIsNotFound() throws Exception {
        when(servicePositionService.getPosition(WORKORDER_ID)).thenThrow(new WorkorderNotFoundException(WORKORDER_ID));

        mockMvc.perform(get(URL, WORKORDER_ID)).andExpect(status().isNotFound());
    }
}
