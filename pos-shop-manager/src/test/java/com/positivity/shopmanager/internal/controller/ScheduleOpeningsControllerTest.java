package com.positivity.shopmanager.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shopmanager.internal.dto.OpeningSearchQuery;
import com.positivity.shopmanager.internal.dto.OpeningSearchResponse;
import com.positivity.shopmanager.internal.dto.OpeningSearchResponse.BayEligibility;
import com.positivity.shopmanager.internal.dto.OpeningSearchResponse.Opening;
import com.positivity.shopmanager.internal.dto.OpeningSearchResponse.StaffingAdvisory;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.AbsenceScope;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.OpeningConstraint;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.SkillFulfillment;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.StaffingAdvisoryCode;
import com.positivity.shopmanager.internal.exception.OpeningSearchPolicyException;
import com.positivity.shopmanager.internal.service.AppointmentsService;
import com.positivity.shopmanager.internal.service.OpeningSearchService;
import com.positivity.shopmanager.internal.service.ScheduleCapacityService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Web-layer contract for {@code GET /v1/schedules/openings} (#2022): gate, parameters, the two envelopes. */
@WebMvcTest(ScheduleController.class)
@Import({GlobalExceptionHandler.class, ScheduleOpeningsControllerTest.FixedClockConfig.class})
@DisplayName("ScheduleController - schedules/openings")
class ScheduleOpeningsControllerTest {

    private static final UUID LOCATION_ID = UUID.fromString("018e1c9f-6b5a-7890-abcd-1234567890ab");
    private static final UUID SERVICE_ID = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a535");
    private static final UUID BAY_ID = UUID.fromString("01960005-0000-7000-8000-0000000000b1");
    private static final UUID TECH_ID = UUID.fromString("01960011-0000-7000-8000-0000000000e1");

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        SecurityExceptionControllerAdvice securityExceptionControllerAdvice() {
            return new SecurityExceptionControllerAdvice();
        }
    }

    @ControllerAdvice
    static class SecurityExceptionControllerAdvice {
        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        void handleAccessDenied() {}
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppointmentsService appointmentsService;

    @MockitoBean
    private ScheduleCapacityService scheduleCapacityService;

    @MockitoBean
    private OpeningSearchService openingSearchService;

    private static OpeningSearchResponse response() {
        OpeningSearchResponse response = new OpeningSearchResponse();
        response.setLocationId(LOCATION_ID);
        response.setTimezone("America/New_York");
        response.setServiceIds(List.of(SERVICE_ID));
        response.setDurationMinutes(90);
        response.setSearchedFrom(Instant.parse("2026-10-04T12:00:00Z"));
        response.setSearchedTo(Instant.parse("2026-11-03T04:00:00Z"));
        response.setRequiredSkillCodes(List.of("A4-SUSPENSION"));
        response.setBayEligibility(BayEligibility.builder()
                .activeBays(3)
                .eligibleBays(1)
                .excludedByCapability(2)
                .build());
        response.setOpenings(List.of(Opening.builder()
                .startAt(Instant.parse("2026-10-04T13:00:00Z"))
                .endAt(Instant.parse("2026-10-04T14:30:00Z"))
                .localDate("2026-10-04")
                .bayId(BAY_ID)
                .bayName("Bay 3")
                .technicianId(TECH_ID)
                .technicianRosterGrain("DAY")
                .skillFulfillment(SkillFulfillment.AWAITING)
                .unmetSkillCodes(List.of("A4-SUSPENSION"))
                .constraintsEvaluated(List.of(
                        OpeningConstraint.HOURS,
                        OpeningConstraint.BAY,
                        OpeningConstraint.DURATION,
                        OpeningConstraint.BUFFER,
                        OpeningConstraint.ROSTER,
                        OpeningConstraint.SKILL))
                .build()));
        response.setStaffingAdvisory(StaffingAdvisory.builder()
                .code(StaffingAdvisoryCode.NO_COMPETENT_MECHANIC_ROSTERED)
                .missingSkillCodes(List.of("A4-SUSPENSION"))
                .absenceScope(AbsenceScope.NOT_AT_THIS_LOCATION)
                .build());
        response.setGeneratedAt(Instant.parse("2026-09-03T12:00:00Z"));
        return response;
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    @DisplayName("200: the A4 contract — openings with per-opening skill state and a response-level advisory")
    void returnsTheContract() throws Exception {
        when(openingSearchService.search(any())).thenReturn(response());

        mockMvc.perform(get("/v1/schedules/openings")
                        .param("locationId", LOCATION_ID.toString())
                        .param("serviceIds", SERVICE_ID.toString())
                        .param("durationMinutes", "90")
                        .param("earliestStart", "2026-10-04T12:00:00Z")
                        .param("technicianId", TECH_ID.toString())
                        .param("horizonDays", "7")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openings[0].startAt").value("2026-10-04T13:00:00Z"))
                .andExpect(jsonPath("$.openings[0].bayName").value("Bay 3"))
                .andExpect(jsonPath("$.openings[0].technicianRosterGrain").value("DAY"))
                .andExpect(jsonPath("$.openings[0].skillFulfillment").value("AWAITING"))
                .andExpect(jsonPath("$.openings[0].unmetSkillCodes[0]").value("A4-SUSPENSION"))
                .andExpect(jsonPath("$.openings[0].constraintsEvaluated[5]").value("SKILL"))
                .andExpect(jsonPath("$.noOpeningReason").doesNotExist())
                .andExpect(jsonPath("$.staffingAdvisory.code").value("NO_COMPETENT_MECHANIC_ROSTERED"))
                .andExpect(jsonPath("$.staffingAdvisory.absenceScope").value("NOT_AT_THIS_LOCATION"))
                .andExpect(jsonPath("$.bayEligibility.eligibleBays").value(1));

        ArgumentCaptor<OpeningSearchQuery> query = ArgumentCaptor.forClass(OpeningSearchQuery.class);
        verify(openingSearchService).search(query.capture());
        assertThat(query.getValue().locationId()).isEqualTo(LOCATION_ID);
        assertThat(query.getValue().serviceIds()).containsExactly(SERVICE_ID);
        assertThat(query.getValue().durationMinutes()).isEqualTo(90);
        assertThat(query.getValue().earliestStart()).isEqualTo(Instant.parse("2026-10-04T12:00:00Z"));
        assertThat(query.getValue().vehicleId()).isNull();
        assertThat(query.getValue().technicianId()).isEqualTo(TECH_ID);
        assertThat(query.getValue().horizonDays()).isEqualTo(7);
        assertThat(query.getValue().limit()).isEqualTo(5);
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    @DisplayName("defaults: horizonDays 30 and limit 10 when omitted")
    void defaults() throws Exception {
        when(openingSearchService.search(any())).thenReturn(response());

        mockMvc.perform(get("/v1/schedules/openings")
                        .param("locationId", LOCATION_ID.toString())
                        .param("serviceIds", SERVICE_ID.toString())
                        .param("durationMinutes", "90")
                        .param("earliestStart", "2026-10-04T12:00:00Z"))
                .andExpect(status().isOk());

        ArgumentCaptor<OpeningSearchQuery> query = ArgumentCaptor.forClass(OpeningSearchQuery.class);
        verify(openingSearchService).search(query.capture());
        assertThat(query.getValue().horizonDays()).isEqualTo(30);
        assertThat(query.getValue().limit()).isEqualTo(10);
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:edit")
    @DisplayName("403 without shop:schedule:view; the service is never asked")
    void forbiddenWithoutPermission() throws Exception {
        mockMvc.perform(get("/v1/schedules/openings")
                        .param("locationId", LOCATION_ID.toString())
                        .param("serviceIds", SERVICE_ID.toString())
                        .param("durationMinutes", "90")
                        .param("earliestStart", "2026-10-04T12:00:00Z"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(openingSearchService);
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    @DisplayName("400: a missing required parameter is syntactic")
    void missingParameterIs400() throws Exception {
        mockMvc.perform(get("/v1/schedules/openings")
                        .param("locationId", LOCATION_ID.toString())
                        .param("serviceIds", SERVICE_ID.toString())
                        .param("earliestStart", "2026-10-04T12:00:00Z"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(openingSearchService);
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    @DisplayName("422: a policy bound exceeded answers the ApiError envelope with the bound's code")
    void policyBoundIs422() throws Exception {
        when(openingSearchService.search(any()))
                .thenThrow(new OpeningSearchPolicyException(OpeningSearchPolicyException.HORIZON_EXCEEDED, "31 > 30"));

        mockMvc.perform(get("/v1/schedules/openings")
                        .param("locationId", LOCATION_ID.toString())
                        .param("serviceIds", SERVICE_ID.toString())
                        .param("durationMinutes", "90")
                        .param("earliestStart", "2026-10-04T12:00:00Z")
                        .param("horizonDays", "31"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OPENING_HORIZON_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("31 > 30"));
    }
}
