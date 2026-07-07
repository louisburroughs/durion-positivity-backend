package com.positivity.people;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.entity.UserPersonLink;
import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.enums.UserLinkStatus;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.internal.repository.UserPersonLinkRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Issue #820 — current-user and person-scoped location/staffing reads.
 *
 * <p>Covers the endpoints the frontend audit probed and found missing:
 * {@code GET /v1/people/me}, {@code /me/locations}, {@code /{id}/locations},
 * {@code /{id}/primary-location} and {@code /{id}/staffing/assignments}.
 */
@DisplayName("Issue #820 person location read contract")
class PersonLocationReadContractIT extends BaseContractIntegrationTest {

    private static final UUID PRIMARY_LOCATION = UUID.fromString("018e1c9f-0000-7000-8000-200000000001");

    private static final UUID SECONDARY_LOCATION = UUID.fromString("018e1c9f-0000-7000-8000-200000000002");

    private static final UUID OLD_LOCATION = UUID.fromString("018e1c9f-0000-7000-8000-200000000003");

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeLocationAssignmentRepository assignmentRepository;

    @Autowired
    private UserPersonLinkRepository userPersonLinkRepository;

    private UUID personId;

    private UUID unassignedPersonId;

    @BeforeEach
    void seedPersonWithAssignments() {
        Person person = personRepository.save(
                Person.builder().firstName("Linked").lastName("Employee").build());
        personId = person.getId();
        Employee employee = employeeRepository.save(Employee.builder()
                .personId(personId)
                .employeeNumber("E-820")
                .status(EmployeeStatus.ACTIVE)
                .hireDate(LocalDate.now().minusYears(1))
                .build());

        UserPersonLink link = new UserPersonLink();
        link.setUsername(TEST_USER);
        link.setPerson(person);
        link.setStatus(UserLinkStatus.ACTIVE);
        userPersonLinkRepository.save(link);

        saveAssignment(
                employee,
                PRIMARY_LOCATION,
                true,
                AssignmentStatus.ACTIVE,
                LocalDate.now().minusDays(30));
        saveAssignment(
                employee,
                SECONDARY_LOCATION,
                false,
                AssignmentStatus.ACTIVE,
                LocalDate.now().minusDays(10));
        saveAssignment(
                employee,
                OLD_LOCATION,
                false,
                AssignmentStatus.ENDED,
                LocalDate.now().minusYears(1));

        Person unassigned = personRepository.save(
                Person.builder().firstName("No").lastName("Location").build());
        unassignedPersonId = unassigned.getId();
        employeeRepository.save(Employee.builder()
                .personId(unassignedPersonId)
                .employeeNumber("E-821")
                .status(EmployeeStatus.ACTIVE)
                .build());
    }

    private void saveAssignment(
            Employee employee, UUID locationId, boolean primary, AssignmentStatus status, LocalDate from) {
        assignmentRepository.save(EmployeeLocationAssignment.builder()
                .employee(employee)
                .locationId(locationId)
                .role("TECHNICIAN")
                .isPrimary(primary)
                .effectiveFrom(from)
                .effectiveTo(status == AssignmentStatus.ENDED ? LocalDate.now().minusDays(60) : null)
                .status(status)
                .createdBy("test")
                .build());
    }

    @Test
    @DisplayName("GET /v1/people/me returns the linked person")
    void meReturnsLinkedPerson() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(personId.toString()));
    }

    @Test
    @DisplayName("GET /v1/people/me returns 404 when the user has no person link")
    void meWithoutLinkReturnsNotFound() throws Exception {
        userPersonLinkRepository.deleteAll();
        mockMvc.perform(withAuth(get("/v1/people/me"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /v1/people/me/locations returns active assignments, primary first")
    void meLocationsReturnsActiveAssignments() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/me/locations")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].locationId").value(PRIMARY_LOCATION.toString()))
                .andExpect(jsonPath("$[0].isPrimary").value(true))
                .andExpect(jsonPath("$[1].locationId").value(SECONDARY_LOCATION.toString()));
    }

    @Test
    @DisplayName("GET /v1/people/{id}/locations returns active assignments for the person")
    void personLocationsReturnsActiveAssignments() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/" + personId + "/locations")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].personId").value(personId.toString()));
    }

    @Test
    @DisplayName("GET /v1/people/{id}/locations returns empty list for a person with no assignments")
    void personLocationsEmptyWhenUnassigned() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/" + unassignedPersonId + "/locations")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /v1/people/{id}/primary-location resolves the primary assignment")
    void personPrimaryLocationResolved() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/" + personId + "/primary-location")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(PRIMARY_LOCATION.toString()));
    }

    @Test
    @DisplayName("GET /v1/people/{id}/primary-location returns 404 when no primary assignment exists")
    void personPrimaryLocationNotFoundWhenAbsent() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/" + unassignedPersonId + "/primary-location")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /v1/people/{id}/staffing/assignments returns the full assignment history")
    void personStaffingAssignmentsReturnsAll() throws Exception {
        mockMvc.perform(withAuth(get("/v1/people/" + personId + "/staffing/assignments")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }
}
