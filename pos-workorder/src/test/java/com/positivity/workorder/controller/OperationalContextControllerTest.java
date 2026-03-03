package com.positivity.workorder.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.internal.dto.OperationalContextOverrideRequest;
import com.positivity.workorder.internal.dto.OperationalContextResponse;
import com.positivity.workorder.internal.dto.WorkorderStartResponse;
import com.positivity.workorder.internal.controller.OperationalContextController;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.service.WorkorderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-layer tests for CAP-140 Story #59 operational context endpoints.
 */
@WebMvcTest(OperationalContextController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class OperationalContextControllerTest {

        private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000059");
        private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000060");

        private static final String GET_URL = "/v1/workorders/{id}/operationalContext";
        private static final String OVERRIDE_URL = "/v1/workorders/{id}/operationalContext/override";
        private static final String START_URL = "/v1/workorders/{id}/start";

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private WorkorderService workorderService;

        // -----------------------------------------------------------------------
        // AC1 — GET returns 200 with OperationalContextResponse body
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("AC1: GET operationalContext returns 200 with context body when workorder exists")
        void whenGetOperationalContext_withExistingWorkorder_thenReturns200WithBody() throws Exception {
                // Issue CAP-140: AC1 — happy path GET
                var ctx = OperationalContextResponse.builder()
                                .version("v1")
                                .locationId(LOCATION_ID)
                                .locked(false)
                                .assignedMechanics(List.of())
                                .assignedResources(List.of())
                                .build();
                when(workorderService.getOperationalContext(WORKORDER_ID)).thenReturn(ctx);

                mockMvc.perform(get(GET_URL, WORKORDER_ID))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.version").value("v1"))
                                .andExpect(jsonPath("$.locked").value(false));
        }

        // -----------------------------------------------------------------------
        // AC2 — GET returns 404 for non-existent workorderId
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("AC2: GET operationalContext returns 404 when workorder not found")
        void whenGetOperationalContext_withUnknownWorkorder_thenReturns404() throws Exception {
                // Issue CAP-140: AC2 — not-found path
                when(workorderService.getOperationalContext(WORKORDER_ID))
                                .thenThrow(new WorkorderNotFoundException(WORKORDER_ID));

                mockMvc.perform(get(GET_URL, WORKORDER_ID))
                                .andExpect(status().isNotFound());
        }

        // -----------------------------------------------------------------------
        // AC3 — POST override returns 200 with updated context
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("AC3: POST override returns 200 with updated operational context")
        void whenOverrideOperationalContext_withValidRequest_thenReturns200WithUpdatedContext() throws Exception {
                // Issue CAP-140: AC3 — happy path override
                var overrideRequest = OperationalContextOverrideRequest.builder()
                                .locationId(LOCATION_ID)
                                .assignedMechanics(List.of())
                                .assignedResources(List.of())
                                .build();
                var updated = OperationalContextResponse.builder()
                                .version("v2")
                                .locationId(LOCATION_ID)
                                .locked(false)
                                .build();
                when(workorderService.overrideOperationalContext(eq(WORKORDER_ID), any()))
                                .thenReturn(updated);

                mockMvc.perform(post(OVERRIDE_URL, WORKORDER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(overrideRequest)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.version").value("v2"));
        }

        // -----------------------------------------------------------------------
        // AC4 — POST override returns 409 when work already started
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("AC4: POST override returns 409 when work has already started")
        void whenOverrideOperationalContext_withWorkAlreadyStarted_thenReturns409() throws Exception {
                // Issue CAP-140: AC4 — conflict when context is locked
                var overrideRequest = OperationalContextOverrideRequest.builder()
                                .locationId(LOCATION_ID)
                                .build();
                when(workorderService.overrideOperationalContext(eq(WORKORDER_ID), any()))
                                .thenThrow(new IllegalStateException(
                                                "Operational context is locked; work already started"));

                mockMvc.perform(post(OVERRIDE_URL, WORKORDER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(overrideRequest)))
                                .andExpect(status().isConflict());
        }

        // -----------------------------------------------------------------------
        // AC5 — POST start returns 200 with WorkorderStartResponse
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("AC5: POST start returns 200 with WorkorderStartResponse")
        void whenStartWork_withEligibleWorkorder_thenReturns200WithStartResponse() throws Exception {
                // Issue CAP-140: AC5 — happy path start
                var startResponse = WorkorderStartResponse.builder()
                                .workorderId(WORKORDER_ID)
                                .operationalContextVersion("v1")
                                .workStartedAt(Instant.now())
                                .build();
                when(workorderService.startWork(eq(WORKORDER_ID), eq("workorder-test-user"), isNull()))
                                .thenReturn(startResponse);

                mockMvc.perform(post(START_URL, WORKORDER_ID))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.workorderId").value(WORKORDER_ID.toString()))
                                .andExpect(jsonPath("$.operationalContextVersion").value("v1"));
        }

        // -----------------------------------------------------------------------
        // AC6 — POST start returns 409 when work already started
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("AC6: POST start returns 409 when work already started")
        void whenStartWork_withWorkAlreadyStarted_thenReturns409() throws Exception {
                // Issue CAP-140: AC6 — conflict on duplicate start
                when(workorderService.startWork(eq(WORKORDER_ID), eq("workorder-test-user"), isNull()))
                                .thenThrow(new IllegalStateException("Work already started on this workorder"));

                mockMvc.perform(post(START_URL, WORKORDER_ID))
                                .andExpect(status().isConflict());
        }
}
