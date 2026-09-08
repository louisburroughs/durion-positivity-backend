package com.positivity.people.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.config.TestSecurityConfig;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.PeopleReportsService;
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
 * The HTTP contract of location scope on the people reports (ADR-0061 §3, #1872): a location
 * outside the caller's reach is a 403 whose body carries {@code LOCATION_SCOPE_DENIED} and never
 * echoes the requested id; a location inside it is a 200. Which locations are in reach is decided
 * in the service and pinned by {@code PeopleReportsLocationScopeTest}.
 */
@WebMvcTest(PeopleReportsController.class)
@Import({TestSecurityConfig.class, WebCommonErrorAutoConfiguration.class, LocationScopeAutoConfiguration.class})
@ActiveProfiles("test")
class PeopleReportsControllerLocationScopeTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);

    private static final UUID OUT_OF_REACH = UUID.fromString("018f0000-0000-7000-8000-0000000000c1");

    private static final UUID IN_REACH = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");

    private static final String EXPORT = PeoplePermissions.ACCOUNTING_TIME_EXPORT;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PeopleReportsService peopleReportsService;

    @Test
    void discrepancyReport_aLocationOutsideTheCallersReachIs403WithTheScopeCode() throws Exception {
        when(peopleReportsService.getAttendanceDiscrepancyReport(
                        any(), any(), anyString(), eq(OUT_OF_REACH), anyList(), anyBoolean(), anyString(), any()))
                .thenThrow(new LocationScopeDeniedException(EXPORT, OUT_OF_REACH.toString()));

        String body = mockMvc.perform(get("/v1/people/reports/attendanceJobtimeDiscrepancy")
                        .param("startDate", "2026-02-01")
                        .param("endDate", "2026-02-07")
                        .param("timezone", "UTC")
                        .param("locationId", OUT_OF_REACH.toString())
                        .header("X-Authorities", EXPORT)
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
    void discrepancyReport_aLocationInsideTheCallersReachIs200() throws Exception {
        when(peopleReportsService.getAttendanceDiscrepancyReport(
                        any(), any(), anyString(), eq(IN_REACH), anyList(), anyBoolean(), anyString(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/people/reports/attendanceJobtimeDiscrepancy")
                        .param("startDate", "2026-02-01")
                        .param("endDate", "2026-02-07")
                        .param("timezone", "UTC")
                        .param("locationId", IN_REACH.toString())
                        .header("X-Authorities", EXPORT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void discrepancyReport_anAbsentLocationIsForwardedAsNullSoTheServiceNarrowsRatherThanGates() throws Exception {
        when(peopleReportsService.getAttendanceDiscrepancyReport(
                        any(), any(), anyString(), isNull(), anyList(), anyBoolean(), anyString(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/people/reports/attendanceJobtimeDiscrepancy")
                        .param("startDate", "2026-02-01")
                        .param("endDate", "2026-02-07")
                        .param("timezone", "UTC")
                        .header("X-Authorities", EXPORT))
                .andExpect(status().isOk());

        verify(peopleReportsService)
                .getAttendanceDiscrepancyReport(
                        any(), any(), eq("UTC"), isNull(), eq(List.of()), eq(false), anyString(), isNull());
    }

    @Test
    void approvedTimeExport_aLocationOutsideTheCallersReachIs403WithTheScopeCode() throws Exception {
        when(peopleReportsService.getApprovedTimeForExport(any(), any(), anyList(), anyString(), any()))
                .thenThrow(new LocationScopeDeniedException(EXPORT, OUT_OF_REACH.toString()));

        String body = mockMvc.perform(get("/v1/people/reports/approvedTime")
                        .param("startDate", "2026-02-01")
                        .param("endDate", "2026-02-07")
                        .param("locationId", IN_REACH.toString(), OUT_OF_REACH.toString())
                        .header("X-Authorities", EXPORT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(OUT_OF_REACH.toString());
    }

    @Test
    void approvedTimeExport_locationsInsideTheCallersReachAre200() throws Exception {
        when(peopleReportsService.getApprovedTimeForExport(any(), any(), eq(List.of(IN_REACH)), anyString(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/people/reports/approvedTime")
                        .param("startDate", "2026-02-01")
                        .param("endDate", "2026-02-07")
                        .param("locationId", IN_REACH.toString())
                        .header("X-Authorities", EXPORT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void withoutTheExportPermissionItIsStillAPlainForbiddenNotAScopeDenial() throws Exception {
        String body = mockMvc.perform(get("/v1/people/reports/approvedTime")
                        .param("startDate", "2026-02-01")
                        .param("endDate", "2026-02-07")
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
