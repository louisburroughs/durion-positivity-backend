package com.positivity.people.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.BaseIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage for the reinstated identity-compliance report (#888): the replica × employee
 * join, response contract, and the {@code people:compliance:view} authority gate.
 */
class PeopleComplianceIT extends BaseIntegrationTest {

    private static final String REPORT_PATH = "/v1/people/compliance/inactive-person-active-user";

    private void seedEmployee(UUID personId, String status, String statusEffectiveAt) {
        jdbcTemplate.update(
                "INSERT INTO employee (id, person_id, status, status_effective_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, CAST(? AS TIMESTAMP WITH TIME ZONE), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                UUID.randomUUID(),
                personId,
                status,
                statusEffectiveAt);
    }

    private void seedLink(UUID linkId, UUID personId, String username, String status) {
        jdbcTemplate.update(
                "INSERT INTO ext_people_contact_user_link "
                        + "(link_id, person_id, username, status, aggregate_version, updated_at) "
                        + "VALUES (?, ?, ?, ?, 1, CURRENT_TIMESTAMP)",
                linkId,
                personId,
                username,
                status);
    }

    @Test
    @DisplayName("Report returns ACTIVE links of SUSPENDED/TERMINATED/DISABLED persons only")
    void reportsOnlyActiveLinksOfInactivePersons() throws Exception {
        UUID terminatedPerson = UUID.fromString("01960011-0000-7000-8000-000000000001");
        UUID activePerson = UUID.fromString("01960011-0000-7000-8000-000000000002");
        UUID suspendedPerson = UUID.fromString("01960011-0000-7000-8000-000000000003");
        seedEmployee(terminatedPerson, "TERMINATED", "2026-01-15T09:30:00Z");
        seedEmployee(activePerson, "ACTIVE", "2026-01-01T00:00:00Z");
        seedEmployee(suspendedPerson, "SUSPENDED", "2026-02-01T00:00:00Z");
        // offending: ACTIVE links on inactive persons
        seedLink(UUID.fromString("01960012-0000-7000-8000-000000000001"), terminatedPerson, "marcus.webb", "ACTIVE");
        seedLink(UUID.fromString("01960012-0000-7000-8000-000000000002"), suspendedPerson, "ana.reyes", "ACTIVE");
        // not offending: active person, and an already-inactive link
        seedLink(UUID.fromString("01960012-0000-7000-8000-000000000003"), activePerson, "ok.user", "ACTIVE");
        seedLink(UUID.fromString("01960012-0000-7000-8000-000000000004"), terminatedPerson, "old.link", "INACTIVE");

        mockMvc.perform(withAuth(get(REPORT_PATH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].username").value("ana.reyes"))
                .andExpect(jsonPath("$[0].personStatus").value("SUSPENDED"))
                .andExpect(jsonPath("$[1].username").value("marcus.webb"))
                .andExpect(jsonPath("$[1].personId").value(terminatedPerson.toString()))
                .andExpect(jsonPath("$[1].personStatus").value("TERMINATED"))
                .andExpect(jsonPath("$[1].personStatusEffectiveAt").value("2026-01-15T09:30:00Z"));
    }

    @Test
    @DisplayName("Empty list when nothing violates the policy")
    void emptyWhenCompliant() throws Exception {
        UUID person = UUID.fromString("01960011-0000-7000-8000-000000000011");
        seedEmployee(person, "ACTIVE", "2026-01-01T00:00:00Z");
        seedLink(UUID.fromString("01960012-0000-7000-8000-000000000011"), person, "fine.user", "ACTIVE");

        mockMvc.perform(withAuth(get(REPORT_PATH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("403 without people:compliance:view")
    void forbiddenWithoutAuthority() throws Exception {
        mockMvc.perform(withAuth(get(REPORT_PATH), "people:employee:view")).andExpect(status().isForbidden());
    }
}
