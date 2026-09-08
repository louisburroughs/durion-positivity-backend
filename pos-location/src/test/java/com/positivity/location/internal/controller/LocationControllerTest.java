package com.positivity.location.internal.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.config.TestSecurityConfig;
import com.positivity.location.internal.dto.LocationParentResponseDTO;
import com.positivity.location.internal.service.LocationRosterService;
import com.positivity.location.internal.service.LocationService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controller-slice contract for {@code POST /v1/locations/{childId}/parents/{parentId}}.
 *
 * <p>Pins the 409 shape the OpenAPI annotations on {@link LocationController#addParent} document:
 * {@code LocationServiceImpl.addParentInternal} rejects a self-parent (and any edge that would close a
 * cycle on the requested {@code parentType}) with
 * {@code ResponseStatusException(CONFLICT, "CYCLE_DETECTED")}, and {@link LocationGlobalExceptionHandler}
 * renders that as an RFC 9457 ProblemDetail carrying the correlation id (ADR-0017 §4). The service is
 * mocked with exactly that exception so the test exercises the controller-to-advice rendering, not the
 * cycle walk itself (covered by {@code LocationServiceCycleGuardIT}).
 */
@WebMvcTest(LocationController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100"})
class LocationControllerTest {

    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000051");
    private static final UUID PARENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000052");
    private static final String CYCLE_DETECTED = "CYCLE_DETECTED";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LocationService locationService;

    @MockitoBean
    LocationRosterService locationRosterService;

    @Test
    @DisplayName("addParent self-parent renders 409 ProblemDetail with detail CYCLE_DETECTED and correlation id")
    void addParent_selfParent_returns409CycleDetectedProblemDetail() throws Exception {
        when(locationService.addParent(eq(LOCATION_ID), eq(LOCATION_ID), eq("PHYSICAL")))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, CYCLE_DETECTED));

        mockMvc.perform(post("/v1/locations/{childId}/parents/{parentId}", LOCATION_ID, LOCATION_ID)
                        .param("parentType", "PHYSICAL")
                        .header(LocationGlobalExceptionHandler.X_CORRELATION_ID, "corr-cycle-409")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(LocationGlobalExceptionHandler.X_CORRELATION_ID, "corr-cycle-409"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value(CYCLE_DETECTED))
                .andExpect(jsonPath("$.instance").value("/v1/locations/" + LOCATION_ID + "/parents/" + LOCATION_ID))
                .andExpect(jsonPath("$.correlationId").value("corr-cycle-409"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @DisplayName("addParent with a valid edge returns 200 with the created relationship")
    void addParent_validEdge_returns200() throws Exception {
        LocationParentResponseDTO created = LocationParentResponseDTO.builder()
                .childId(LOCATION_ID)
                .parentId(PARENT_ID)
                .parentType("PHYSICAL")
                .build();
        when(locationService.addParent(eq(LOCATION_ID), eq(PARENT_ID), eq("PHYSICAL")))
                .thenReturn(created);

        mockMvc.perform(post("/v1/locations/{childId}/parents/{parentId}", LOCATION_ID, PARENT_ID)
                        .param("parentType", "PHYSICAL")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.childId").value(LOCATION_ID.toString()))
                .andExpect(jsonPath("$.parentId").value(PARENT_ID.toString()))
                .andExpect(jsonPath("$.parentType").value("PHYSICAL"));
    }
}
