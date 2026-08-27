package com.positivity.people.contract;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.positivity.people.BaseIntegrationTest;
import com.positivity.people.internal.entity.TimePeriod;
import com.positivity.people.internal.enums.TimePeriodStatus;
import com.positivity.people.internal.repository.TimePeriodRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

@DisplayName("Time Period Management ContractIT")
class TimePeriodManagementContractIT extends BaseIntegrationTest {

    private static final String TIME_PERIOD_AUTHORITIES = "people:timePeriod:create,people:timePeriod:transition";

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    @Autowired
    private TimePeriodRepository timePeriodRepository;

    private TimePeriod seedOpenPeriod() {
        TimePeriod period = new TimePeriod();
        period.setTenantId(TENANT_ID);
        period.setStartDate(LocalDate.of(2026, 6, 1));
        period.setEndDate(LocalDate.of(2026, 6, 14));
        period.setStatus(TimePeriodStatus.OPEN);
        return timePeriodRepository.save(period);
    }

    @Test
    @DisplayName("POST /v1/people/time-periods creates an OPEN period")
    void createTimePeriod() throws Exception {
        mockMvc.perform(withAuth(post("/v1/people/time-periods"), TIME_PERIOD_AUTHORITIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","startDate":"2026-06-01","endDate":"2026-06-14"}
                                """.formatted(TENANT_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.timePeriodId", notNullValue()))
                .andExpect(jsonPath("$.tenantId", is(TENANT_ID.toString())))
                .andExpect(jsonPath("$.startDate", is("2026-06-01")))
                .andExpect(jsonPath("$.endDate", is("2026-06-14")))
                .andExpect(jsonPath("$.status", is("OPEN")));
    }

    @Test
    @DisplayName("POST /v1/people/time-periods rejects an overlapping range with 409")
    void createOverlappingPeriod() throws Exception {
        seedOpenPeriod();
        mockMvc.perform(withAuth(post("/v1/people/time-periods"), TIME_PERIOD_AUTHORITIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","startDate":"2026-06-10","endDate":"2026-06-20"}
                                """.formatted(TENANT_ID)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /v1/people/time-periods rejects an inverted range with 400")
    void createInvertedRange() throws Exception {
        mockMvc.perform(withAuth(post("/v1/people/time-periods"), TIME_PERIOD_AUTHORITIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","startDate":"2026-06-14","endDate":"2026-06-01"}
                                """.formatted(TENANT_ID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /v1/people/time-periods/{id}/status closes submissions")
    void transitionTimePeriod() throws Exception {
        TimePeriod period = seedOpenPeriod();
        mockMvc.perform(withAuth(
                                post("/v1/people/time-periods/{id}/status", period.getTimePeriodId()),
                                TIME_PERIOD_AUTHORITIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUBMISSION_CLOSED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUBMISSION_CLOSED")));
    }

    @Test
    @DisplayName("POST /v1/people/time-periods/{id}/status refuses leaving PAYROLL_CLOSED with 409")
    void transitionOutOfPayrollClosed() throws Exception {
        TimePeriod period = seedOpenPeriod();
        period.setStatus(TimePeriodStatus.PAYROLL_CLOSED);
        timePeriodRepository.save(period);

        mockMvc.perform(withAuth(
                                post("/v1/people/time-periods/{id}/status", period.getTimePeriodId()),
                                TIME_PERIOD_AUTHORITIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"OPEN"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /v1/people/time-periods/{id}/status returns 404 for an unknown period")
    void transitionUnknownPeriod() throws Exception {
        mockMvc.perform(withAuth(
                                post(
                                        "/v1/people/time-periods/{id}/status",
                                        UUID.fromString("dddddddd-0000-0000-0000-000000000001")),
                                TIME_PERIOD_AUTHORITIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUBMISSION_CLOSED"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("endpoints require their timePeriod permissions")
    void permissionsEnforced() throws Exception {
        mockMvc.perform(withAuth(post("/v1/people/time-periods"), "people:timekeeping:view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","startDate":"2026-06-01","endDate":"2026-06-14"}
                                """.formatted(TENANT_ID)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/v1/people/time-periods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
