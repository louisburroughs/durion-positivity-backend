package com.positivity.people.contract;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.positivity.people.BaseIntegrationTest;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.entity.TimePeriod;
import com.positivity.people.internal.entity.TimekeepingEntry;
import com.positivity.people.internal.enums.ApprovalStatus;
import com.positivity.people.internal.enums.TimePeriodStatus;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.internal.repository.TimePeriodRepository;
import com.positivity.people.internal.repository.TimekeepingEntryRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

@DisplayName("Timekeeping Approval ContractIT")
class TimekeepingApprovalContractIT extends BaseIntegrationTest {

    private static final String TIMEKEEPING_AUTHORITIES =
            "people:timekeeping:view,people:timekeeping:approve,people:timekeeping:reject";

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID PERSON_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private TimePeriodRepository timePeriodRepository;
    @Autowired
    private TimekeepingEntryRepository timekeepingEntryRepository;

    private Person seedPerson() {
        return personRepository.findById(PERSON_ID).orElseGet(() ->
                personRepository.save(Person.builder()
                        .id(PERSON_ID)
                        .firstName("Jane")
                        .lastName("Approver")
                        .primaryEmail("jane.approver@example.com")
                        .build()));
    }

    private TimePeriod seedTimePeriod() {
        TimePeriod period = new TimePeriod();
        period.setTimePeriodId(UUID.fromString("cccccccc-0000-0000-0000-000000000001"));
        period.setTenantId(TENANT_ID);
        period.setStartDate(LocalDate.of(2026, 6, 1));
        period.setEndDate(LocalDate.of(2026, 6, 30));
        period.setStatus(TimePeriodStatus.SUBMISSION_CLOSED);
        return timePeriodRepository.save(period);
    }

    private TimekeepingEntry seedEntry(UUID personId, Instant start, Instant end) {
        TimekeepingEntry entry = new TimekeepingEntry();
        entry.setTimekeepingEntryId(UUID.randomUUID());
        entry.setTenantId(TENANT_ID);
        entry.setEmployeeId(personId);
        entry.setSourceSystem("shopmgr");
        entry.setSourceSessionId(UUID.randomUUID());
        entry.setSessionStartTime(start);
        entry.setSessionEndTime(end);
        entry.setApprovalStatus(ApprovalStatus.PENDING_APPROVAL);
        return timekeepingEntryRepository.save(entry);
    }

    @Test
    @DisplayName("CP-664-001: GET /approvals/people returns employees with timekeeping entries")
    void listApprovalPeople_returnsEmployees() throws Exception {
        seedPerson();
        seedEntry(PERSON_ID,
                Instant.parse("2026-06-05T09:00:00Z"),
                Instant.parse("2026-06-05T17:00:00Z"));

        mockMvc.perform(withAuth(get("/v1/people/timekeeping/approvals/people"), TIMEKEEPING_AUTHORITIES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].personId", notNullValue()))
                .andExpect(jsonPath("$[0].displayName", notNullValue()));
    }

    @Test
    @DisplayName("CP-664-002: GET /time-periods returns list of periods")
    void listTimePeriods_returnsPeriods() throws Exception {
        seedTimePeriod();

        mockMvc.perform(withAuth(get("/v1/people/timekeeping/time-periods"), TIMEKEEPING_AUTHORITIES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].timePeriodId", notNullValue()))
                .andExpect(jsonPath("$[0].startDate", notNullValue()))
                .andExpect(jsonPath("$[0].endDate", notNullValue()))
                .andExpect(jsonPath("$[0].status", notNullValue()));
    }

    @Test
    @DisplayName("CP-664-003: GET /timekeeping-entries returns entries for person in period")
    void listTimekeepingEntries_returnsEntries() throws Exception {
        seedPerson();
        TimePeriod period = seedTimePeriod();
        seedEntry(PERSON_ID,
                Instant.parse("2026-06-10T08:00:00Z"),
                Instant.parse("2026-06-10T16:00:00Z"));

        mockMvc.perform(withAuth(
                        get("/v1/people/timekeeping/timekeeping-entries")
                                .param("personId", PERSON_ID.toString())
                                .param("timePeriodId", period.getTimePeriodId().toString()),
                        TIMEKEEPING_AUTHORITIES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].timekeepingEntryId", notNullValue()))
                .andExpect(jsonPath("$[0].approvalStatus", is("PENDING_APPROVAL")));
    }

    @Test
    @DisplayName("CP-664-004: GET /time-period-approvals returns approval summary")
    void getTimePeriodApproval_returnsSummary() throws Exception {
        seedPerson();
        TimePeriod period = seedTimePeriod();
        seedEntry(PERSON_ID,
                Instant.parse("2026-06-15T08:00:00Z"),
                Instant.parse("2026-06-15T16:00:00Z"));

        mockMvc.perform(withAuth(
                        get("/v1/people/timekeeping/time-period-approvals")
                                .param("personId", PERSON_ID.toString())
                                .param("timePeriodId", period.getTimePeriodId().toString()),
                        TIMEKEEPING_AUTHORITIES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personId", is(PERSON_ID.toString())))
                .andExpect(jsonPath("$.pendingCount", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.overallStatus", is("PENDING_APPROVAL")));
    }

    @Test
    @DisplayName("CP-664-005: POST approve bulk-approves PENDING_APPROVAL entries")
    void approvePeriod_approvesEntries() throws Exception {
        seedPerson();
        TimePeriod period = seedTimePeriod();
        seedEntry(PERSON_ID,
                Instant.parse("2026-06-20T08:00:00Z"),
                Instant.parse("2026-06-20T16:00:00Z"));

        mockMvc.perform(withAuth(
                        post("/v1/people/timekeeping/time-periods/{timePeriodId}/people/{personId}/approve",
                                period.getTimePeriodId(), PERSON_ID),
                        TIMEKEEPING_AUTHORITIES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedCount", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.status", is("APPROVED")));
    }

    @Test
    @DisplayName("CP-664-006: POST reject bulk-rejects PENDING_APPROVAL entries")
    void rejectPeriod_rejectsEntries() throws Exception {
        seedPerson();
        TimePeriod period = seedTimePeriod();
        seedEntry(PERSON_ID,
                Instant.parse("2026-06-25T08:00:00Z"),
                Instant.parse("2026-06-25T16:00:00Z"));

        String body = """
                { "reason": "Discrepancy with shop records" }
                """;

        mockMvc.perform(withAuth(
                        post("/v1/people/timekeeping/time-periods/{timePeriodId}/people/{personId}/reject",
                                period.getTimePeriodId(), PERSON_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        TIMEKEEPING_AUTHORITIES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedCount", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.status", is("REJECTED")));
    }

    @Test
    @DisplayName("VE-664-001: POST reject without reason returns 400")
    void rejectPeriod_missingReason_returns400() throws Exception {
        seedPerson();
        TimePeriod period = seedTimePeriod();

        mockMvc.perform(withAuth(
                        post("/v1/people/timekeeping/time-periods/{timePeriodId}/people/{personId}/reject",
                                period.getTimePeriodId(), PERSON_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"),
                        TIMEKEEPING_AUTHORITIES))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("VE-664-002: GET /timekeeping-entries for unknown period returns 404")
    void listTimekeepingEntries_unknownPeriod_returns404() throws Exception {
        mockMvc.perform(withAuth(
                        get("/v1/people/timekeeping/timekeeping-entries")
                                .param("personId", PERSON_ID.toString())
                                .param("timePeriodId", UUID.randomUUID().toString()),
                        TIMEKEEPING_AUTHORITIES))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("VE-664-003: endpoints require authentication (no auth header → 401)")
    void endpoints_withoutAuth_return401() throws Exception {
        mockMvc.perform(get("/v1/people/timekeeping/approvals/people"))
                .andExpect(status().isUnauthorized());
    }
}
