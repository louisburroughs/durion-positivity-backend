package com.positivity.people.internal.controller;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.config.TestSecurityConfig;
import com.positivity.people.internal.dto.WorkSessionClockStateResponse;
import com.positivity.people.internal.enums.ClockState;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.WorkSessionService;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract of {@code GET /v1/people/workSessions/current} (issue #2061): the read is
 * gated on {@code people:self:view} or {@code people:timekeeping:view}, an omitted personId is
 * passed through as the caller's own, CLOCKED_OUT is a 200, and the service's refusals render
 * as 403 with the platform's error codes.
 */
@WebMvcTest(WorkSessionController.class)
@Import({
    TestSecurityConfig.class,
    WebCommonErrorAutoConfiguration.class,
    LocationScopeAutoConfiguration.class,
    WorkSessionControllerTest.FixedClockConfig.class
})
@ActiveProfiles("test")
class WorkSessionControllerTest {

    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID SESSION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkSessionService workSessionService;

    @Test
    @DisplayName("AC6: a person with nothing open is 200 CLOCKED_OUT, not 404")
    void currentIsClockedOutRatherThanNotFound() throws Exception {
        when(workSessionService.getCurrentClockState(PERSON_ID))
                .thenReturn(WorkSessionClockStateResponse.builder()
                        .personId(PERSON_ID)
                        .clockState(ClockState.CLOCKED_OUT)
                        .build());

        mockMvc.perform(get("/v1/people/workSessions/current")
                        .param("personId", PERSON_ID.toString())
                        .header("X-Authorities", PeoplePermissions.TIMEKEEPING_VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personId").value(PERSON_ID.toString()))
                .andExpect(jsonPath("$.clockState").value("CLOCKED_OUT"))
                .andExpect(jsonPath("$.workSessionId").doesNotExist())
                .andExpect(jsonPath("$.clockedInAt").doesNotExist())
                .andExpect(jsonPath("$.breakStartedAt").doesNotExist());
    }

    @Test
    @DisplayName(
            "AC7: an omitted personId reaches the service as null (the caller's own person), under people:self:view")
    void omittedPersonIdIsTheCallersOwn() throws Exception {
        when(workSessionService.getCurrentClockState(isNull()))
                .thenReturn(WorkSessionClockStateResponse.builder()
                        .personId(PERSON_ID)
                        .clockState(ClockState.ON_BREAK)
                        .workSessionId(SESSION_ID)
                        .clockedInAt(Instant.parse("2026-02-16T08:00:00Z"))
                        .breakStartedAt(Instant.parse("2026-02-16T12:00:00Z"))
                        .build());

        mockMvc.perform(get("/v1/people/workSessions/current").header("X-Authorities", PeoplePermissions.SELF_VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clockState").value("ON_BREAK"))
                .andExpect(jsonPath("$.workSessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.clockedInAt").value("2026-02-16T08:00:00Z"))
                .andExpect(jsonPath("$.breakStartedAt").value("2026-02-16T12:00:00Z"));
    }

    @Test
    @DisplayName("neither people:self:view nor people:timekeeping:view: 403 before the service is reached")
    void withoutEitherPermissionIsForbidden() throws Exception {
        mockMvc.perform(get("/v1/people/workSessions/current")
                        .param("personId", PERSON_ID.toString())
                        .header("X-Authorities", "workorder:workorder:view"))
                .andExpect(status().isForbidden());

        verify(workSessionService, never()).getCurrentClockState(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("the policy's refusal of another person's state renders as 403 FORBIDDEN")
    void policyRefusalIsForbidden() throws Exception {
        when(workSessionService.getCurrentClockState(PERSON_ID)).thenThrow(new AccessDeniedException("not yours"));

        mockMvc.perform(get("/v1/people/workSessions/current")
                        .param("personId", PERSON_ID.toString())
                        .header("X-Authorities", PeoplePermissions.SELF_VIEW))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("a scoped supervisor outside the person's location renders as 403 LOCATION_SCOPE_DENIED")
    void scopeRefusalIsLocationScopeDenied() throws Exception {
        when(workSessionService.getCurrentClockState(PERSON_ID))
                .thenThrow(new LocationScopeDeniedException(PeoplePermissions.TIMEKEEPING_VIEW, "unassigned"));

        mockMvc.perform(get("/v1/people/workSessions/current")
                        .param("personId", PERSON_ID.toString())
                        .header("X-Authorities", PeoplePermissions.TIMEKEEPING_VIEW))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
    }

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-02-16T10:00:00Z"), ZoneOffset.UTC);
        }
    }
}
