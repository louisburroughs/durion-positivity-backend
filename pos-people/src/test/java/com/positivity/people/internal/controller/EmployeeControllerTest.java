package com.positivity.people.internal.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.config.TestSecurityConfig;
import com.positivity.people.internal.dto.EmployeeAddressDto;
import com.positivity.people.internal.dto.EmployeeContactInfoDto;
import com.positivity.people.internal.dto.EmployeeEmergencyContactDto;
import com.positivity.people.internal.dto.EmployeeProfileDto;
import com.positivity.people.internal.dto.EmployeeSummaryDto;
import com.positivity.people.internal.dto.PagedResponse;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.service.EmployeeService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

@WebMvcTest(EmployeeController.class)
@Import({TestSecurityConfig.class, EmployeeControllerTest.FixedClockConfig.class})
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class EmployeeControllerTest {

    private static final UUID EMPLOYEE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c01");
    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c02");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    EmployeeService employeeService;

    /**
     * The authorities a TECHNICIAN carries over other people's records: the structural reads, and
     * nothing that returns personal contact detail. Named for the role because #1898 is about what
     * that role could reach, not about a permission in the abstract.
     */
    private static final String TECHNICIAN_AUTHORITIES = "people:employee:view,people:self:view";

    private EmployeeSummaryDto summary() {
        return EmployeeSummaryDto.builder()
                .employeeId(EMPLOYEE_ID)
                .personId(PERSON_ID)
                .employeeNumber("EMP-0001")
                .firstName("Jane")
                .lastName("Smith")
                .preferredName("Janie")
                .status("ACTIVE")
                .active(true)
                .build();
    }

    private EmployeeProfileDto profile() {
        var address = new EmployeeAddressDto();
        address.setLine1("42 Maple Street");
        address.setCity("Springfield");
        address.setRegion("IL");
        address.setPostalCode("62704");
        address.setCountry("US");

        var emergencyContact = new EmployeeEmergencyContactDto();
        emergencyContact.setName("John Smith");
        emergencyContact.setRelationship("SPOUSE");
        emergencyContact.setPhone("+1-555-987-6543");

        var contactInfo = new EmployeeContactInfoDto();
        contactInfo.setPrimaryEmail("jane.smith@example.com");
        contactInfo.setPrimaryPhone("+1-555-123-4567");
        contactInfo.setAddress(address);
        contactInfo.setEmergencyContact(emergencyContact);

        return EmployeeProfileDto.builder()
                .id(EMPLOYEE_ID)
                .firstName("Jane")
                .lastName("Smith")
                .employeeNumber("EMP-0001")
                .status(EmployeeStatus.ACTIVE)
                .hireDate(LocalDate.of(2026, 1, 15))
                .contactInfo(contactInfo)
                .build();
    }

    // ─── GET /v1/people/employees — 200 OK ────────────────────────────────

    @Test
    void searchEmployees_returnsOkWithMatchingResults_whenCallerHoldsThePermission() throws Exception {
        PagedResponse<EmployeeSummaryDto> page = new PagedResponse<>(List.of(summary()), 0, 20, 1, 1);
        when(employeeService.searchEmployees(eq("smith"), eq(0), eq(20))).thenReturn(page);

        mockMvc.perform(get("/v1/people/employees").param("q", "smith").header("X-Authorities", "people:employee:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].employeeNumber").value("EMP-0001"))
                .andExpect(jsonPath("$.items[0].firstName").value("Jane"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void searchEmployees_appliesDefaultPagingWhenOmitted() throws Exception {
        PagedResponse<EmployeeSummaryDto> page = new PagedResponse<>(List.of(), 0, 20, 0, 0);
        when(employeeService.searchEmployees(eq(null), eq(0), eq(20))).thenReturn(page);

        mockMvc.perform(get("/v1/people/employees").header("X-Authorities", "people:employee:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void searchEmployees_returnsForbidden_whenCallerLacksThePermission() throws Exception {
        mockMvc.perform(get("/v1/people/employees").header("X-Authorities", "people:employee:create"))
                .andExpect(status().isForbidden());
    }

    @Test
    void searchEmployees_rejectsASizeAboveTheMaximum() throws Exception {
        mockMvc.perform(get("/v1/people/employees")
                        .param("size", "101")
                        .header("X-Authorities", "people:employee:view"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchEmployees_rejectsANegativePage() throws Exception {
        mockMvc.perform(get("/v1/people/employees").param("page", "-1").header("X-Authorities", "people:employee:view"))
                .andExpect(status().isBadRequest());
    }

    // ─── GET /v1/people/employees/{employeeId} — the PII gate (#1898) ─────

    /**
     * The disclosure #1898 reports. {@code people:employee:view} is seeded to twelve roles
     * including TECHNICIAN and SERVICE_ADVISOR, and none of the endpoints it guards is
     * location-scoped, so before the split a technician could pick a colleague out of the
     * company-wide employee search and then read their home address and emergency contact here.
     */
    @Test
    void getEmployee_returnsForbidden_whenCallerHoldsOnlyTheTechnicianStructuralReads() throws Exception {
        mockMvc.perform(get("/v1/people/employees/{employeeId}", EMPLOYEE_ID)
                        .header("X-Authorities", TECHNICIAN_AUTHORITIES))
                .andExpect(status().isForbidden());
    }

    @Test
    void getEmployee_returnsProfileWithContactInfo_whenCallerHoldsThePiiPermission() throws Exception {
        when(employeeService.getEmployee(eq(EMPLOYEE_ID))).thenReturn(profile());

        mockMvc.perform(get("/v1/people/employees/{employeeId}", EMPLOYEE_ID)
                        .header("X-Authorities", "people:employee:view,people:employee_pii:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeNumber").value("EMP-0001"))
                .andExpect(jsonPath("$.contactInfo.address.line1").value("42 Maple Street"))
                .andExpect(jsonPath("$.contactInfo.emergencyContact.name").value("John Smith"))
                .andExpect(jsonPath("$.contactInfo.primaryPhone").value("+1-555-123-4567"));
    }

    /**
     * The PII permission is the whole gate, not an extra on top of the structural one: a role
     * seeded only the narrow permission still reads the profile. This pins the split as a move
     * rather than an AND, so a later reader does not "restore" people:employee:view here.
     */
    @Test
    void getEmployee_returnsOk_whenCallerHoldsThePiiPermissionAlone() throws Exception {
        when(employeeService.getEmployee(eq(EMPLOYEE_ID))).thenReturn(profile());

        mockMvc.perform(get("/v1/people/employees/{employeeId}", EMPLOYEE_ID)
                        .header("X-Authorities", "people:employee_pii:view"))
                .andExpect(status().isOk());
    }

    /**
     * The other half of the split: the technician keeps the structural reads. A fix that revoked
     * the employee search from the roles that need a colleague picker would pass the test above
     * and still be wrong.
     */
    @Test
    void searchEmployees_stillSucceeds_forTheTechnicianAuthoritiesDeniedTheProfile() throws Exception {
        PagedResponse<EmployeeSummaryDto> page = new PagedResponse<>(List.of(summary()), 0, 20, 1, 1);
        when(employeeService.searchEmployees(eq("smith"), eq(0), eq(20))).thenReturn(page);

        mockMvc.perform(get("/v1/people/employees").param("q", "smith").header("X-Authorities", TECHNICIAN_AUTHORITIES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].employeeNumber").value("EMP-0001"))
                // the summary row carries no contact block at all, which is why it can stay on the
                // permission every staff role holds
                .andExpect(jsonPath("$.items[0].contactInfo").doesNotExist());
    }

    /**
     * A real fixed {@code Clock}, not a mock. {@code PeopleExceptionHandler} reads it on every
     * error response ({@code Instant.now(clock)}), and an unstubbed mock returns {@code null} —
     * which made the advice itself throw, so the original exception surfaced as unhandled
     * (issue #1716). Fixed rather than {@code systemUTC} so timestamps stay deterministic.
     */
    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
