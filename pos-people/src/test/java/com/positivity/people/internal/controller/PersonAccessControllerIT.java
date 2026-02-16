package com.positivity.people.internal.controller;

import com.positivity.people.BaseIntegrationTest;
import com.positivity.people.internal.client.dto.Role;
import com.positivity.people.internal.client.dto.RoleAssignment;
import com.positivity.people.internal.client.dto.RoleAssignmentRequest;
import com.positivity.people.internal.exception.NotFoundException;
import com.positivity.people.service.PeopleAccessControlService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PersonAccessControllerIT extends BaseIntegrationTest {

    @MockitoBean
    private PeopleAccessControlService peopleAccessControlService;

    @Test
    void getRoles_returns200() throws Exception {
        UUID personId = UUID.randomUUID();
        when(peopleAccessControlService.getRolesForPerson())
                .thenReturn(List.of(new Role(UUID.randomUUID(), "MANAGER", "Manager role")));

        mockMvc.perform(withAuth(get("/v1/people/{personId}/access/roles", personId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("MANAGER"));
    }

    @Test
    void getAssignments_returns200() throws Exception {
        UUID personId = UUID.randomUUID();
        RoleAssignment assignment = new RoleAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setUserId(UUID.randomUUID());
        assignment.setRoleId(UUID.randomUUID());

        when(peopleAccessControlService.getAssignmentsForPerson(personId, false)).thenReturn(List.of(assignment));

        mockMvc.perform(withAuth(get("/v1/people/{personId}/access/assignments", personId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(assignment.getId().toString()));
    }

    @Test
    void getAssignments_returns404WhenPersonLinkMissing() throws Exception {
        UUID personId = UUID.randomUUID();
        when(peopleAccessControlService.getAssignmentsForPerson(personId, true))
                .thenThrow(new NotFoundException("No user link found"));

        mockMvc.perform(withAuth(get("/v1/people/{personId}/access/assignments", personId)
                .param("includeHistory", "true")))
                .andExpect(status().isNotFound());
    }

    @Test
    void createAssignment_returns201() throws Exception {
        UUID personId = UUID.randomUUID();
        RoleAssignment created = new RoleAssignment();
        created.setId(UUID.randomUUID());

        when(peopleAccessControlService.createAssignmentForPerson(eq(personId), any(RoleAssignmentRequest.class)))
                .thenReturn(created);

        String payload = """
                {
                  "roleId": "%s",
                  "scopeType": "GLOBAL"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(withAuth(post("/v1/people/{personId}/access/assignments", personId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(created.getId().toString()));
    }

    @Test
    void revokeAssignment_returns204() throws Exception {
        UUID personId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        doNothing().when(peopleAccessControlService)
                .revokeAssignmentForPerson(assignmentId, LocalDate.parse("2026-02-16"));

        mockMvc.perform(
                withAuth(delete("/v1/people/{personId}/access/assignments/{assignmentId}", personId, assignmentId)
                        .param("endDate", "2026-02-16")))
                .andExpect(status().isNoContent());
    }
}