package com.positivity.people.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.config.TestSecurityConfig;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.PeopleAvailabilityService;
import com.positivity.people.internal.service.StaffingAssignmentService;
import com.positivity.people.internal.service.UserPersonTranslationService;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract of location scope on {@code GET /v1/people/availability} (ADR-0061 §3,
 * #1872): a location outside the caller's reach is a 403 whose body carries
 * {@code LOCATION_SCOPE_DENIED} and never echoes the requested id; a location inside it is a
 * 200. The decision itself lives in the service and is pinned by
 * {@code PeopleAvailabilityLocationScopeTest}.
 */
@WebMvcTest(PeopleAvailabilityController.class)
@Import({TestSecurityConfig.class, WebCommonErrorAutoConfiguration.class, LocationScopeAutoConfiguration.class})
@ActiveProfiles("test")
class PeopleAvailabilityControllerLocationScopeTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);

    private static final UUID OUT_OF_REACH = UUID.fromString("018f0000-0000-7000-8000-0000000000c1");

    private static final UUID IN_REACH = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");

    private static final String VIEW = PeoplePermissions.AVAILABILITY_VIEW;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PeopleAvailabilityService peopleAvailabilityService;

    @MockitoBean
    private StaffingAssignmentService staffingAssignmentService;

    @MockitoBean
    private UserPersonTranslationService userPersonTranslationService;

    @Test
    void aLocationOutsideTheCallersReachIs403WithTheScopeCodeAndNoEchoOfTheId() throws Exception {
        when(peopleAvailabilityService.getPeopleAvailability(eq(OUT_OF_REACH), any()))
                .thenThrow(new LocationScopeDeniedException(VIEW, OUT_OF_REACH.toString()));

        String body = mockMvc.perform(get("/v1/people/availability")
                        .param("locationId", OUT_OF_REACH.toString())
                        .header("X-Authorities", VIEW)
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
    void aLocationInsideTheCallersReachIs200AndTheFilterReachesTheService() throws Exception {
        when(peopleAvailabilityService.getPeopleAvailability(eq(IN_REACH), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/people/availability")
                        .param("locationId", IN_REACH.toString())
                        .header("X-Authorities", VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(peopleAvailabilityService).getPeopleAvailability(eq(IN_REACH), isNull());
    }

    @Test
    void anAbsentLocationIsForwardedAsNullSoTheServiceNarrowsRatherThanGates() throws Exception {
        when(peopleAvailabilityService.getPeopleAvailability(isNull(), any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/people/availability").header("X-Authorities", VIEW))
                .andExpect(status().isOk());

        verify(peopleAvailabilityService).getPeopleAvailability(isNull(), isNull());
    }

    @Test
    void withoutTheViewPermissionItIsStillAPlainForbiddenNotAScopeDenial() throws Exception {
        String body = mockMvc.perform(get("/v1/people/availability")
                        .param("locationId", IN_REACH.toString())
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
