package com.positivity.people.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.BaseIntegrationTest;
import com.positivity.people.internal.client.dto.RoleDto;
import com.positivity.people.internal.client.dto.UserRoleDto;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.service.PeopleAccessControlService;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class PersonAccessControllerIT extends BaseIntegrationTest {

    @MockitoBean
    private PeopleAccessControlService peopleAccessControlService;

    @Test
    void getRoles_returns200() throws Exception {
        UUID personUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(peopleAccessControlService.getAvailableRolesForPerson(personUuid))
                .thenReturn(List.of(
                        RoleDto.builder().code("MANAGER").name("Manager role").build()));

        mockMvc.perform(withAuth(get("/v1/people/{personUuid}/access/roles", personUuid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("MANAGER"));
    }

    @Test
    void getRoles_returns404WhenPersonNotFound() throws Exception {
        UUID personUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(peopleAccessControlService.getAvailableRolesForPerson(personUuid))
                .thenThrow(new PersonNotFoundException(personUuid));

        mockMvc.perform(withAuth(get("/v1/people/{personUuid}/access/roles", personUuid)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Person not found with id: " + personUuid))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void getAssignments_returns200() throws Exception {
        UUID personUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UserRoleDto assignment =
                UserRoleDto.builder().userId("user-1").roleCode("MANAGER").build();

        when(peopleAccessControlService.getPersonRoleAssignments(personUuid, false, null))
                .thenReturn(List.of(assignment));

        mockMvc.perform(withAuth(get("/v1/people/{personUuid}/access/assignments", personUuid)
                        .param("includeHistory", "false")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roleCode").value("MANAGER"));
    }

    @Test
    void getAssignments_defaultsToFalseWhenIncludeHistoryOmitted() throws Exception {
        UUID personUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UserRoleDto assignment =
                UserRoleDto.builder().userId("user-1").roleCode("MANAGER").build();

        // Should default to false when includeHistory is not provided
        when(peopleAccessControlService.getPersonRoleAssignments(personUuid, false, null))
                .thenReturn(List.of(assignment));

        mockMvc.perform(withAuth(get("/v1/people/{personUuid}/access/assignments", personUuid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roleCode").value("MANAGER"));
    }

    @Test
    void getAssignments_returns404WhenPersonLinkMissing() throws Exception {
        UUID personUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(peopleAccessControlService.getPersonRoleAssignments(personUuid, true, null))
                .thenThrow(new EntityNotFoundException("No user link found"));

        mockMvc.perform(withAuth(get("/v1/people/{personUuid}/access/assignments", personUuid)
                        .param("includeHistory", "true")))
                .andExpect(status().isNotFound());
    }

    @Test
    void createAssignment_returns201() throws Exception {
        UUID personUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UserRoleDto created = UserRoleDto.builder().roleCode("MANAGER").build();

        when(peopleAccessControlService.assignRoleToPerson(eq(personUuid), eq("MANAGER"), any(), any(), any()))
                .thenReturn(created);

        String payload = """
				{
				  "roleCode": "MANAGER"
				}
				""";

        mockMvc.perform(withAuth(post("/v1/people/{personUuid}/access/assignments", personUuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roleCode").value("MANAGER"));
    }

    @Test
    void revokeAssignment_returns204() throws Exception {
        UUID personUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String roleCode = "MANAGER";
        doNothing()
                .when(peopleAccessControlService)
                .revokeRoleFromPerson(personUuid, roleCode, LocalDateTime.parse("2026-02-16T00:00:00"));

        mockMvc.perform(withAuth(delete("/v1/people/{personUuid}/access/assignments/{roleCode}", personUuid, roleCode)
                        .param("endDate", "2026-02-16T00:00:00")))
                .andExpect(status().isNoContent());
    }
}
