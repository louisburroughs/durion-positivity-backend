package com.positivity.people.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.config.TestSecurityConfig;
import com.positivity.people.internal.dto.EmployeeAddressDto;
import com.positivity.people.internal.dto.EmployeeContactInfoDto;
import com.positivity.people.internal.dto.EmployeeEmergencyContactDto;
import com.positivity.people.internal.dto.EmployeeProfileDto;
import com.positivity.people.internal.dto.EmployeeSearchResponse;
import com.positivity.people.internal.dto.EmployeeSummaryDto;
import com.positivity.people.internal.dto.PagedResponse;
import com.positivity.people.internal.enums.AllowedAction;
import com.positivity.people.internal.enums.EmployeeSearchInclude;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.exception.RequestValidationException;
import com.positivity.people.internal.exception.ResourceStateConflictException;
import com.positivity.people.internal.service.EmployeeService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
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
        EmployeeSearchResponse response = new EmployeeSearchResponse(page, Map.of(EmployeeStatus.ACTIVE, 1L));
        when(employeeService.searchEmployees(eq("smith"), eq(null), eq("lastName,asc"), eq(0), eq(20), eq(null)))
                .thenReturn(response);

        mockMvc.perform(get("/v1/people/employees").param("q", "smith").header("X-Authorities", "people:employee:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.items[0].employeeNumber").value("EMP-0001"))
                .andExpect(jsonPath("$.page.items[0].firstName").value("Jane"))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.page").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.statusCounts.ACTIVE").value(1));
    }

    @Test
    void searchEmployees_appliesDefaultPagingWhenOmitted() throws Exception {
        PagedResponse<EmployeeSummaryDto> page = new PagedResponse<>(List.of(), 0, 20, 0, 0);
        EmployeeSearchResponse response = new EmployeeSearchResponse(page, Map.of());
        when(employeeService.searchEmployees(eq(null), eq(null), eq("lastName,asc"), eq(0), eq(20), eq(null)))
                .thenReturn(response);

        mockMvc.perform(get("/v1/people/employees").header("X-Authorities", "people:employee:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.items").isEmpty());
    }

    @Test
    void searchEmployees_passesRepeatedStatusParamsAndAnExplicitSortThrough() throws Exception {
        PagedResponse<EmployeeSummaryDto> page = new PagedResponse<>(List.of(summary()), 0, 20, 1, 1);
        EmployeeSearchResponse response = new EmployeeSearchResponse(page, Map.of(EmployeeStatus.ACTIVE, 1L));
        when(employeeService.searchEmployees(
                        eq(null),
                        eq(List.of(EmployeeStatus.ACTIVE, EmployeeStatus.DISABLED)),
                        eq("lastName,desc"),
                        eq(0),
                        eq(20),
                        eq(null)))
                .thenReturn(response);

        mockMvc.perform(get("/v1/people/employees")
                        .param("status", "ACTIVE", "DISABLED")
                        .param("sort", "lastName,desc")
                        .header("X-Authorities", "people:employee:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.items[0].employeeNumber").value("EMP-0001"));
    }

    // ─── GET /v1/people/employees?include= — register enrichment (durion#2155) ────

    @Test
    void searchEmployees_passesRepeatedIncludeParamsThrough() throws Exception {
        PagedResponse<EmployeeSummaryDto> page = new PagedResponse<>(List.of(summary()), 0, 20, 1, 1);
        EmployeeSearchResponse response = new EmployeeSearchResponse(page, Map.of(EmployeeStatus.ACTIVE, 1L));
        when(employeeService.searchEmployees(
                        eq(null),
                        eq(null),
                        eq("lastName,asc"),
                        eq(0),
                        eq(20),
                        eq(List.of(EmployeeSearchInclude.USERNAME, EmployeeSearchInclude.ROLE_ASSIGNMENTS))))
                .thenReturn(response);

        mockMvc.perform(get("/v1/people/employees")
                        .param("include", "USERNAME", "ROLE_ASSIGNMENTS")
                        .header("X-Authorities", "people:employee:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.items[0].employeeNumber").value("EMP-0001"));
    }

    /** The controller-level half of "include= absent leaves the response identical to today's thin shape". */
    @Test
    void searchEmployees_omitsEveryEnrichmentField_whenIncludeIsAbsent() throws Exception {
        PagedResponse<EmployeeSummaryDto> page = new PagedResponse<>(List.of(summary()), 0, 20, 1, 1);
        EmployeeSearchResponse response = new EmployeeSearchResponse(page, Map.of(EmployeeStatus.ACTIVE, 1L));
        when(employeeService.searchEmployees(eq(null), eq(null), eq("lastName,asc"), eq(0), eq(20), eq(null)))
                .thenReturn(response);

        mockMvc.perform(get("/v1/people/employees").header("X-Authorities", "people:employee:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.items[0].username").doesNotExist())
                .andExpect(jsonPath("$.page.items[0].contactInfo").doesNotExist())
                .andExpect(jsonPath("$.page.items[0].roleAssignments").doesNotExist())
                .andExpect(jsonPath("$.page.items[0].primaryLocation").doesNotExist())
                .andExpect(jsonPath("$.page.items[0].otherLocationCount").doesNotExist())
                .andExpect(jsonPath("$.page.items[0].jobRole").doesNotExist())
                .andExpect(jsonPath("$.page.items[0].allowedActions").doesNotExist());
    }

    // ─── allowedActions (durion#2159) — the field is serialized, not just computed ─────

    @Test
    @DisplayName("EmployeeProfileDto.allowedActions is serialized on the single-employee read")
    void getEmployee_serializesAllowedActions() throws Exception {
        EmployeeProfileDto profileWithActions = profile();
        profileWithActions.setAllowedActions(
                List.of(AllowedAction.VIEW_PII, AllowedAction.UPDATE, AllowedAction.DISABLE));
        when(employeeService.getEmployee(eq(EMPLOYEE_ID))).thenReturn(profileWithActions);

        mockMvc.perform(get("/v1/people/employees/{employeeId}", EMPLOYEE_ID)
                        .header("X-Authorities", "people:employee_pii:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.allowedActions", org.hamcrest.Matchers.containsInAnyOrder("VIEW_PII", "UPDATE", "DISABLE")));
    }

    @Test
    @DisplayName("EmployeeSummaryDto.allowedActions is serialized when include=ALLOWED_ACTIONS is requested")
    void searchEmployees_serializesAllowedActions_whenIncludeRequestsIt() throws Exception {
        EmployeeSummaryDto summaryWithActions = summary();
        summaryWithActions.setAllowedActions(List.of(AllowedAction.VIEW_PII));
        PagedResponse<EmployeeSummaryDto> page = new PagedResponse<>(List.of(summaryWithActions), 0, 20, 1, 1);
        EmployeeSearchResponse response = new EmployeeSearchResponse(page, Map.of(EmployeeStatus.ACTIVE, 1L));
        when(employeeService.searchEmployees(
                        eq(null),
                        eq(null),
                        eq("lastName,asc"),
                        eq(0),
                        eq(20),
                        eq(List.of(EmployeeSearchInclude.ALLOWED_ACTIONS))))
                .thenReturn(response);

        mockMvc.perform(get("/v1/people/employees")
                        .param("include", "ALLOWED_ACTIONS")
                        .header("X-Authorities", "people:employee:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.items[0].allowedActions", org.hamcrest.Matchers.contains("VIEW_PII")));
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

    @Test
    void searchEmployees_rejectsAnUnsupportedSortField() throws Exception {
        when(employeeService.searchEmployees(eq(null), eq(null), eq("employeeNumber,asc"), eq(0), eq(20), eq(null)))
                .thenThrow(new RequestValidationException("Unsupported sort field: 'employeeNumber'"));

        mockMvc.perform(get("/v1/people/employees")
                        .param("sort", "employeeNumber,asc")
                        .header("X-Authorities", "people:employee:view"))
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
        EmployeeSearchResponse response = new EmployeeSearchResponse(page, Map.of(EmployeeStatus.ACTIVE, 1L));
        when(employeeService.searchEmployees(eq("smith"), eq(null), eq("lastName,asc"), eq(0), eq(20), eq(null)))
                .thenReturn(response);

        mockMvc.perform(get("/v1/people/employees").param("q", "smith").header("X-Authorities", TECHNICIAN_AUTHORITIES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.items[0].employeeNumber").value("EMP-0001"))
                // the summary row carries no contact block at all, which is why it can stay on the
                // permission every staff role holds
                .andExpect(jsonPath("$.page.items[0].contactInfo").doesNotExist());
    }

    // ─── GET /v1/people/employees/by-number/{employeeNumber} — 404 envelope (#1720) ─

    @Test
    void resolveByNumber_returns404ApiError_whenNoEmployeeHasThatNumber() throws Exception {
        when(employeeService.resolveByEmployeeNumber(eq("EMP-9999"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/people/employees/by-number/{employeeNumber}", "EMP-9999")
                        .header("X-Authorities", "people:employee:view"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    // ─── POST /v1/people/employees/{employeeId}/enable — the DISABLED -> ACTIVE return edge ─

    @Test
    void enableEmployee_returnsOk_whenCallerHoldsTheActivationPermission() throws Exception {
        EmployeeProfileDto activated = profile();
        activated.setStatus(EmployeeStatus.ACTIVE);
        when(employeeService.enableEmployee(eq(EMPLOYEE_ID), any())).thenReturn(activated);

        mockMvc.perform(post("/v1/people/employees/{employeeId}/enable", EMPLOYEE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"updatedAt\":\"2026-02-01T14:05:00Z\"}")
                        .header("X-Authorities", "people:employee:activation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void enableEmployee_returnsForbidden_whenCallerLacksTheActivationPermission() throws Exception {
        // people:employee:edit is the profile-edit permission, deliberately distinct from
        // people:employee:activation — an editor must not be able to silently restore sign-in.
        mockMvc.perform(post("/v1/people/employees/{employeeId}/enable", EMPLOYEE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"updatedAt\":\"2026-02-01T14:05:00Z\"}")
                        .header("X-Authorities", "people:employee:edit"))
                .andExpect(status().isForbidden());
    }

    @Test
    void enableEmployee_returnsConflict_whenTheServiceRejectsTheStateTransition() throws Exception {
        when(employeeService.enableEmployee(eq(EMPLOYEE_ID), any()))
                .thenThrow(new ResourceStateConflictException("Only DISABLED employees can be enabled"));

        mockMvc.perform(post("/v1/people/employees/{employeeId}/enable", EMPLOYEE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"updatedAt\":\"2026-02-01T14:05:00Z\"}")
                        .header("X-Authorities", "people:employee:activation"))
                .andExpect(status().isConflict());
    }

    @Test
    void enableEmployee_rejectsAMissingConcurrencyToken() throws Exception {
        mockMvc.perform(post("/v1/people/employees/{employeeId}/enable", EMPLOYEE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("X-Authorities", "people:employee:activation"))
                .andExpect(status().isBadRequest());
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
