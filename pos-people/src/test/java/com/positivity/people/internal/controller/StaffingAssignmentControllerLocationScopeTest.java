package com.positivity.people.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.config.TestSecurityConfig;
import com.positivity.people.internal.dto.StaffingAssignmentResponse;
import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.StaffingAssignmentService;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract of location scope on the staffing-assignment mutations (ADR-0061 §3,
 * #1872): an assignment location outside the caller's reach is a 403 whose body carries
 * {@code LOCATION_SCOPE_DENIED} and never echoes the location; one inside it succeeds. The
 * decision itself lives in the service and is pinned by
 * {@code StaffingAssignmentLocationScopeTest}.
 */
@WebMvcTest(StaffingAssignmentController.class)
@Import({TestSecurityConfig.class, WebCommonErrorAutoConfiguration.class, LocationScopeAutoConfiguration.class})
@ActiveProfiles("test")
class StaffingAssignmentControllerLocationScopeTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);

    private static final UUID PERSON_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");

    private static final UUID ASSIGNMENT_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000f1");

    private static final UUID OUT_OF_REACH = UUID.fromString("018f0000-0000-7000-8000-0000000000c1");

    private static final UUID IN_REACH = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");

    private static final String EDIT = PeoplePermissions.EMPLOYEE_EDIT;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StaffingAssignmentService staffingAssignmentService;

    private static String requestBody(UUID locationId) {
        return """
                {"personId":"%s","locationId":"%s","role":"TECHNICIAN","isPrimary":true,"effectiveFrom":"2026-02-16"}
                """.formatted(PERSON_ID, locationId);
    }

    private static StaffingAssignmentResponse responseAt(UUID locationId) {
        return new StaffingAssignmentResponse(
                ASSIGNMENT_ID,
                PERSON_ID,
                locationId,
                "TECHNICIAN",
                true,
                AssignmentStatus.ACTIVE,
                LocalDate.of(2026, 2, 16),
                null,
                TEST_CLOCK.instant(),
                TEST_CLOCK.instant(),
                "hr.user");
    }

    @Test
    void create_aLocationOutsideTheCallersReachIs403WithTheScopeCodeAndNoEchoOfTheId() throws Exception {
        when(staffingAssignmentService.create(any(), anyString()))
                .thenThrow(new LocationScopeDeniedException(EDIT, OUT_OF_REACH.toString()));

        String body = mockMvc.perform(post("/v1/people/staffing/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(OUT_OF_REACH))
                        .header("X-Authorities", EDIT)
                        .header("X-Correlation-Id", "cid-1872"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.correlationId").value("cid-1872"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(OUT_OF_REACH.toString());
    }

    @Test
    void create_aLocationInsideTheCallersReachIs201() throws Exception {
        when(staffingAssignmentService.create(any(), anyString())).thenReturn(responseAt(IN_REACH));

        mockMvc.perform(post("/v1/people/staffing/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(IN_REACH))
                        .header("X-Authorities", EDIT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.locationId").value(IN_REACH.toString()));
    }

    @Test
    void update_aLocationOutsideTheCallersReachIs403WithTheScopeCode() throws Exception {
        when(staffingAssignmentService.update(eq(ASSIGNMENT_ID), any(), anyString()))
                .thenThrow(new LocationScopeDeniedException(EDIT, OUT_OF_REACH.toString()));

        String body = mockMvc.perform(put("/v1/people/staffing/assignments/{id}", ASSIGNMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(OUT_OF_REACH))
                        .header("X-Authorities", EDIT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(OUT_OF_REACH.toString());
    }

    @Test
    void update_aLocationInsideTheCallersReachIs200() throws Exception {
        when(staffingAssignmentService.update(eq(ASSIGNMENT_ID), any(), anyString()))
                .thenReturn(Optional.of(responseAt(IN_REACH)));

        mockMvc.perform(put("/v1/people/staffing/assignments/{id}", ASSIGNMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(IN_REACH))
                        .header("X-Authorities", EDIT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(IN_REACH.toString()));
    }

    @Test
    void end_anAssignmentOutsideTheCallersReachIs403WithTheScopeCode() throws Exception {
        doThrow(new LocationScopeDeniedException(EDIT, OUT_OF_REACH.toString()))
                .when(staffingAssignmentService)
                .end(ASSIGNMENT_ID);

        mockMvc.perform(delete("/v1/people/staffing/assignments/{id}", ASSIGNMENT_ID)
                        .header("X-Authorities", EDIT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
    }

    @Test
    void end_anAssignmentInsideTheCallersReachIs204() throws Exception {
        mockMvc.perform(delete("/v1/people/staffing/assignments/{id}", ASSIGNMENT_ID)
                        .header("X-Authorities", EDIT))
                .andExpect(status().isNoContent());
    }

    @Test
    void withoutTheEditPermissionItIsStillAPlainForbiddenNotAScopeDenial() throws Exception {
        String body = mockMvc.perform(post("/v1/people/staffing/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(IN_REACH))
                        .header("X-Authorities", "people:employee:view"))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(LocationScopeDeniedException.ERROR_CODE);
    }

    @TestConfiguration
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }
    }
}
