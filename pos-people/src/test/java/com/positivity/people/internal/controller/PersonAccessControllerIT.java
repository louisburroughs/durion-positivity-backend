package com.positivity.people.internal.controller;

import com.positivity.people.BaseIntegrationTest;
import com.positivity.people.internal.client.dto.RoleDto;
import com.positivity.people.internal.client.dto.UserRoleDto;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.service.PeopleAccessControlService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
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

@SpringBootTest
class PersonAccessControllerIT extends BaseIntegrationTest {

        @MockitoBean
        private PeopleAccessControlService peopleAccessControlService;

        @Test
        void getRoles_returns200() throws Exception {
                UUID personUuid = UUID.randomUUID();
                when(peopleAccessControlService.getAvailableRolesForPerson(personUuid))
                                .thenReturn(List.of(RoleDto.builder().code("MANAGER").name("Manager role").build()));

                mockMvc.perform(withAuth(get("/v1/people/{personUuid}/access/roles", personUuid)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].code").value("MANAGER"));
        }

        @Test
        void getRoles_returns404WhenPersonNotFound() throws Exception {
                UUID personUuid = UUID.randomUUID();
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
                UUID personUuid = UUID.randomUUID();
                UserRoleDto assignment = UserRoleDto.builder().userId("user-1").roleCode("MANAGER").build();

                when(peopleAccessControlService.getPersonRoleAssignments(personUuid, false, null))
                                .thenReturn(List.of(assignment));

                mockMvc.perform(withAuth(get("/v1/people/{personUuid}/access/assignments", personUuid)
                                .param("includeHistory", "false")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].roleCode").value("MANAGER"));
        }

        @Test
        void getAssignments_returns404WhenPersonLinkMissing() throws Exception {
                UUID personUuid = UUID.randomUUID();
                when(peopleAccessControlService.getPersonRoleAssignments(personUuid, true, null))
                                .thenThrow(new EntityNotFoundException("No user link found"));

                mockMvc.perform(withAuth(get("/v1/people/{personUuid}/access/assignments", personUuid)
                                .param("includeHistory", "true")))
                                .andExpect(status().isNotFound());
        }

        @Test
        void createAssignment_returns201() throws Exception {
                UUID personUuid = UUID.randomUUID();
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
                UUID personUuid = UUID.randomUUID();
                String roleCode = "MANAGER";
                doNothing().when(peopleAccessControlService)
                                .revokeRoleFromPerson(personUuid, roleCode, LocalDateTime.parse("2026-02-16T00:00:00"));

                mockMvc.perform(
                                withAuth(delete("/v1/people/{personUuid}/access/assignments/{roleCode}", personUuid,
                                                roleCode)
                                                .param("endDate", "2026-02-16T00:00:00")))
                                .andExpect(status().isNoContent());
        }
}