package com.positivity.peoplecontact.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.peoplecontact.BaseIntegrationTest;
import com.positivity.peoplecontact.internal.client.dto.RoleDto;
import com.positivity.peoplecontact.internal.client.dto.UserRoleDto;
import com.positivity.peoplecontact.internal.exception.PersonNotFoundException;
import com.positivity.peoplecontact.internal.service.PeopleAccessControlService;
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
                .thenReturn(List.of(RoleDto.builder()
                        .name("MANAGER")
                        .description("Manager role")
                        .build()));

        mockMvc.perform(withAuth(get("/v1/people/{personUuid}/access/roles", personUuid)))
                .andExpect(status().isOk())
                // The role name is the stable code callers select by; both are published so a
                // picker reading either field gets the same value instead of a null.
                .andExpect(jsonPath("$[0].name").value("MANAGER"))
                .andExpect(jsonPath("$[0].code").value("MANAGER"))
                .andExpect(jsonPath("$[0].scopeType").doesNotExist());
    }

    @Test
    void getRoles_returns404WhenPersonNotFound() throws Exception {
        UUID personUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(peopleAccessControlService.getAvailableRolesForPerson(personUuid))
                .thenThrow(new PersonNotFoundException(personUuid));

        mockMvc.perform(withAuth(get("/v1/people/{personUuid}/access/roles", personUuid)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("PERSON_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Person not found with id: " + personUuid))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.correlationId").exists());
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
        UserRoleDto created = UserRoleDto.builder()
                .userId("00000000-0000-0000-0000-000000000002")
                .roleCode("MANAGER")
                .startDate(LocalDateTime.parse("2026-09-01T00:00:00"))
                .endDate(LocalDateTime.parse("2026-12-31T23:59:59"))
                .active(true)
                .build();

        when(peopleAccessControlService.assignRoleToPerson(eq(personUuid), eq("MANAGER"), any(), any()))
                .thenReturn(created);

        String payload = """
                {
                  "roleCode": "MANAGER",
                  "startDate": "2026-09-01T00:00:00",
                  "endDate": "2026-12-31T23:59:59"
                }
                """;

        mockMvc.perform(withAuth(post("/v1/people/{personUuid}/access/assignments", personUuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roleCode").value("MANAGER"))
                // ADR-0061: the assignment is a dated user-to-role link, so no location comes
                // back on it. The time of day the caller supplied is echoed, not truncated.
                .andExpect(jsonPath("$.startDate").value("2026-09-01T00:00:00"))
                .andExpect(jsonPath("$.endDate").value("2026-12-31T23:59:59"))
                .andExpect(jsonPath("$.locationId").doesNotExist());
    }

    /**
     * ADR-0061 removed location scope from role assignments, so {@code locationId} is gone from
     * the published request contract: the endpoint must still succeed for a body that carries
     * only what the contract declares, and nothing about a location reaches the service.
     */
    @Test
    void createAssignment_forwardsNoLocationToTheService() throws Exception {
        UUID personUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(peopleAccessControlService.assignRoleToPerson(eq(personUuid), eq("MANAGER"), any(), any()))
                .thenReturn(UserRoleDto.builder().roleCode("MANAGER").build());

        mockMvc.perform(withAuth(post("/v1/people/{personUuid}/access/assignments", personUuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\": \"MANAGER\"}")))
                .andExpect(status().isCreated());

        verify(peopleAccessControlService).assignRoleToPerson(personUuid, "MANAGER", null, null);
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
