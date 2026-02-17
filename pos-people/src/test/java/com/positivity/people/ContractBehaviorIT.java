package com.positivity.people;

import com.positivity.people.internal.client.SecurityServiceException;
import com.positivity.people.internal.client.dto.UserRoleDto;
import com.positivity.people.service.PeopleAccessControlService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
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

@DisplayName("People Access Control ContractBehaviorIT")
class ContractBehaviorIT extends BaseIntegrationTest {

    @MockitoBean
    private PeopleAccessControlService peopleAccessControlService;

    @Test
    @DisplayName("happy path: list assignments with includeHistory")
    void listAssignments_happyPath() throws Exception {
        UUID personUuid = UUID.randomUUID();
        UserRoleDto assignment = UserRoleDto.builder()
                .userId("11111111-1111-1111-1111-111111111111")
                .roleCode("SHOP_MANAGER")
                .locationId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
                .startDate(LocalDate.parse("2026-01-01"))
                .endDate(null)
                .active(true)
                .build();

        when(peopleAccessControlService.getPersonRoleAssignments(personUuid, true, null))
                .thenReturn(List.of(assignment));

        mockMvc.perform(withAuth(get("/v1/people/{personUuid}/access/assignments", personUuid)
                        .param("includeHistory", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roleCode").value("SHOP_MANAGER"))
                .andExpect(jsonPath("$[0].locationId").value("22222222-2222-2222-2222-222222222222"));
    }

    @Test
    @DisplayName("happy path: create assignment using contract example")
    void createAssignment_happyPath() throws Exception {
        UUID personUuid = UUID.randomUUID();
        UserRoleDto created = UserRoleDto.builder()
                .userId("11111111-1111-1111-1111-111111111111")
                .roleCode("SHOP_MANAGER")
                .locationId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
                .startDate(LocalDate.parse("2026-02-16"))
                .active(true)
                .build();

        when(peopleAccessControlService.assignRoleToPerson(eq(personUuid), eq("SHOP_MANAGER"), any(), any(), any()))
                .thenReturn(created);

        String payload = """
                {
                  "roleCode": "SHOP_MANAGER",
                  "locationId": "22222222-2222-2222-2222-222222222222",
                  "startDate": "2026-02-16T00:00:00",
                  "endDate": null
                }
                """;

        mockMvc.perform(withAuth(post("/v1/people/{personUuid}/access/assignments", personUuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roleCode").value("SHOP_MANAGER"))
                .andExpect(jsonPath("$.locationId").value("22222222-2222-2222-2222-222222222222"));
    }

    @Test
    @DisplayName("validation: reject missing roleCode")
    void createAssignment_rejectsMissingRoleCode() throws Exception {
        UUID personUuid = UUID.randomUUID();

        mockMvc.perform(withAuth(post("/v1/people/{personUuid}/access/assignments", personUuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"22222222-2222-2222-2222-222222222222\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("validation: reject endDate earlier than startDate")
    void createAssignment_rejectsInvalidDateWindow() throws Exception {
        UUID personUuid = UUID.randomUUID();

        when(peopleAccessControlService.assignRoleToPerson(eq(personUuid), eq("SHOP_MANAGER"), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("endDate must be greater than or equal to startDate"));

        String payload = """
                {
                  "roleCode": "SHOP_MANAGER",
                  "locationId": "22222222-2222-2222-2222-222222222222",
                  "startDate": "2026-06-01T00:00:00",
                  "endDate": "2026-05-31T23:59:59"
                }
                """;

        mockMvc.perform(withAuth(post("/v1/people/{personUuid}/access/assignments", personUuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("endDate must be greater than or equal to startDate"));
    }

    @Test
    @DisplayName("auth failure: unauthenticated request is rejected")
    void createAssignment_unauthenticatedRejected() throws Exception {
        UUID personUuid = UUID.randomUUID();

        mockMvc.perform(post("/v1/people/{personUuid}/access/assignments", personUuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"SHOP_MANAGER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("concurrency invariant: overlap conflict maps to 409")
    void createAssignment_overlapConflict() throws Exception {
        UUID personUuid = UUID.randomUUID();

        when(peopleAccessControlService.assignRoleToPerson(eq(personUuid), eq("MECHANIC"), any(), any(), any()))
                .thenThrow(new SecurityServiceException("Overlapping assignments are not allowed", 409));

        String payload = """
                {
                  "roleCode": "MECHANIC",
                  "locationId": "22222222-2222-2222-2222-222222222222",
                  "startDate": "2026-06-01T00:00:00",
                  "endDate": "2026-08-01T00:00:00"
                }
                """;

        mockMvc.perform(withAuth(post("/v1/people/{personUuid}/access/assignments", personUuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Overlapping assignments are not allowed"));
    }

    @Test
    @DisplayName("happy path: revoke assignment returns 204")
    void revokeAssignment_happyPath() throws Exception {
        UUID personUuid = UUID.randomUUID();
        String roleCode = "SHOP_MANAGER";

        doNothing().when(peopleAccessControlService)
                .revokeRoleFromPerson(personUuid, roleCode, LocalDateTime.parse("2026-12-31T23:59:59"));

        mockMvc.perform(withAuth(delete("/v1/people/{personUuid}/access/assignments/{roleCode}", personUuid, roleCode)
                        .param("endDate", "2026-12-31T23:59:59")))
                .andExpect(status().isNoContent());
    }
}
