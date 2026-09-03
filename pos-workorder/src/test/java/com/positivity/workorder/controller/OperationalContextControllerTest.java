package com.positivity.workorder.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.internal.controller.OperationalContextController;
import com.positivity.workorder.internal.dto.OperationalContextOverrideRequest;
import com.positivity.workorder.internal.dto.OperationalContextResponse;
import com.positivity.workorder.internal.dto.WorkorderStartResponse;
import com.positivity.workorder.internal.enums.ResourceType;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.service.WorkorderService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

/**
 * Controller-layer tests for CAP-140 Story #59 operational context endpoints.
 */
@WebMvcTest(OperationalContextController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class OperationalContextControllerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

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

        mockMvc.perform(get(GET_URL, WORKORDER_ID)).andExpect(status().isNotFound());
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
    // #1656: the wire shape names the resource, not "the bay"
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("#1656: override accepts resourceType and the response serialises resourceId + resourceType")
    void whenOverrideOperationalContext_withMobileUnit_thenWireShapeIsTypeNeutral() throws Exception {
        // The retired bayId key was echoed straight back from the request and named a bay whatever
        // the assignment actually pointed at; it is gone rather than deprecated (pre-production).
        UUID unitId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        var overrideRequest = OperationalContextOverrideRequest.builder()
                .locationId(LOCATION_ID)
                .resourceType(ResourceType.MOBILE_UNIT)
                .assignedResources(List.of(unitId))
                .build();
        var updated = OperationalContextResponse.builder()
                .version("v2")
                .locationId(LOCATION_ID)
                .resourceId(unitId.toString())
                .resourceType(ResourceType.MOBILE_UNIT)
                .locked(false)
                .build();
        when(workorderService.overrideOperationalContext(eq(WORKORDER_ID), any()))
                .thenReturn(updated);

        mockMvc.perform(post(OVERRIDE_URL, WORKORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overrideRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId").value(unitId.toString()))
                .andExpect(jsonPath("$.resourceType").value("MOBILE_UNIT"))
                .andExpect(jsonPath("$.bayId").doesNotExist());

        ArgumentCaptor<OperationalContextOverrideRequest> captor =
                ArgumentCaptor.forClass(OperationalContextOverrideRequest.class);
        verify(workorderService).overrideOperationalContext(eq(WORKORDER_ID), captor.capture());
        assertThat(captor.getValue().getResourceType()).isEqualTo(ResourceType.MOBILE_UNIT);
    }

    @Test
    @DisplayName("#1656: an unrecognised resourceType on the override body is a 400, not a 200 typed BAY")
    void whenOverrideOperationalContext_withUnknownResourceType_thenReturns400() throws Exception {
        // The enum used to carry a global @JsonCreator so the Kafka listener could tolerate an
        // upstream producer's garbage. Being global, it also governed this synchronous endpoint:
        // "MOBILE-UNIT" (a hyphen, an entirely plausible typo) bound to "absent", fell through to
        // the BAY default and returned 200 having pointed the workorder at a van while typing it as
        // a bay — the half-applied state the write path exists to prevent. A synchronous caller can
        // be told it sent garbage, so the body binds strictly (ADR-0017).
        mockMvc.perform(post(OVERRIDE_URL, WORKORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + LOCATION_ID + "\",\"resourceType\":\"MOBILE-UNIT\","
                                + "\"assignedResources\":[\"00000000-0000-0000-0000-0000000000c1\"]}"))
                .andExpect(status().isBadRequest());

        // Nothing reached the write path, so no workorder was left half-updated.
        verify(workorderService, never()).overrideOperationalContext(any(), any());
    }

    @Test
    @DisplayName("#1656: a lower-case resourceType is a 400 too — leniency belongs to the event path only")
    void whenOverrideOperationalContext_withMisCasedResourceType_thenReturns400() throws Exception {
        mockMvc.perform(post(OVERRIDE_URL, WORKORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + LOCATION_ID + "\",\"resourceType\":\"mobile_unit\"}"))
                .andExpect(status().isBadRequest());

        verify(workorderService, never()).overrideOperationalContext(any(), any());
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
                .thenThrow(new IllegalStateException("Operational context is locked; work already started"));

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
                .workStartedAt(Instant.now(TEST_CLOCK))
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

        mockMvc.perform(post(START_URL, WORKORDER_ID)).andExpect(status().isConflict());
    }
}
