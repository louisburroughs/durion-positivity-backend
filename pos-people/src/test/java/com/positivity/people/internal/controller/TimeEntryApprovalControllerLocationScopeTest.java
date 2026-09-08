package com.positivity.people.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.config.TestSecurityConfig;
import com.positivity.people.internal.dto.PagedResponse;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.TimeEntryService;
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
 * The HTTP contract of location scope on {@code GET /v1/people/timeEntries} (ADR-0061 §3,
 * #1871): a location outside the caller's reach is a 403 whose body carries
 * {@code LOCATION_SCOPE_DENIED} — distinguishable from a plain permission failure — and never
 * echoes the requested id; a location inside it is a 200. Which locations are in reach is decided
 * in the service and pinned by {@code TimeEntryLocationScopeTest} against a real replica.
 *
 * <p>{@link LocationScopeAutoConfiguration} is imported explicitly because {@code @WebMvcTest}
 * does not load auto-configurations from other artifacts; in the running service it is registered
 * through {@code AutoConfiguration.imports}.
 */
@WebMvcTest(TimeEntryApprovalController.class)
@Import({TestSecurityConfig.class, WebCommonErrorAutoConfiguration.class, LocationScopeAutoConfiguration.class})
@ActiveProfiles("test")
class TimeEntryApprovalControllerLocationScopeTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);

    private static final UUID OUT_OF_REACH = UUID.fromString("018f0000-0000-7000-8000-0000000000c1");

    private static final UUID IN_REACH = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TimeEntryService timeEntryService;

    @Test
    void aLocationOutsideTheCallersReachIs403WithTheScopeCodeAndNoEchoOfTheId() throws Exception {
        when(timeEntryService.listTimeEntries(any(), any(), any(), any(), eq(OUT_OF_REACH), anyInt(), anyInt()))
                .thenThrow(new LocationScopeDeniedException(PeoplePermissions.TIMEENTRY_VIEW, OUT_OF_REACH.toString()));

        String body = mockMvc.perform(get("/v1/people/timeEntries")
                        .param("locationId", OUT_OF_REACH.toString())
                        .header("X-Authorities", PeoplePermissions.TIMEENTRY_VIEW)
                        .header("X-Correlation-Id", "cid-1871"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.correlationId").value("cid-1871"))
                .andExpect(header().string("X-Correlation-Id", "cid-1871"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(OUT_OF_REACH.toString());
    }

    @Test
    void aLocationInsideTheCallersReachIs200AndTheFilterReachesTheService() throws Exception {
        when(timeEntryService.listTimeEntries(any(), any(), any(), any(), eq(IN_REACH), anyInt(), anyInt()))
                .thenReturn(new PagedResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/v1/people/timeEntries")
                        .param("locationId", IN_REACH.toString())
                        .header("X-Authorities", PeoplePermissions.TIMEENTRY_VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));

        verify(timeEntryService)
                .listTimeEntries(isNull(), isNull(), eq(ZoneOffset.UTC), isNull(), eq(IN_REACH), eq(0), eq(20));
    }

    @Test
    void anAbsentLocationIsForwardedAsNullSoTheServiceNarrowsRatherThanGates() throws Exception {
        when(timeEntryService.listTimeEntries(any(), any(), any(), any(), isNull(), anyInt(), anyInt()))
                .thenReturn(new PagedResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/v1/people/timeEntries").header("X-Authorities", PeoplePermissions.TIMEENTRY_VIEW))
                .andExpect(status().isOk());

        verify(timeEntryService)
                .listTimeEntries(isNull(), isNull(), eq(ZoneOffset.UTC), isNull(), isNull(), eq(0), eq(20));
    }

    @Test
    void withoutTheViewPermissionItIsStillAPlainForbiddenNotAScopeDenial() throws Exception {
        String body = mockMvc.perform(get("/v1/people/timeEntries")
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
