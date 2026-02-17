package com.positivity.people;

import com.positivity.people.internal.client.SecurityServiceException;
import com.positivity.people.internal.dto.AttendanceDiscrepancyReportResponse;
import com.positivity.people.internal.dto.TimeEntryDecisionResult;
import com.positivity.people.internal.client.dto.UserRoleDto;
import com.positivity.people.service.PeopleAccessControlService;
import com.positivity.people.service.PeopleReportsService;
import com.positivity.people.service.TimeEntryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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

                                @MockitoBean
                                private TimeEntryService timeEntryService;

                                @MockitoBean
                                private PeopleReportsService peopleReportsService;

                                @Test
                                @DisplayName("happy path: attendance discrepancy report returns aggregated counts")
                                void attendanceDiscrepancyReport_happyPath() throws Exception {
                                                                when(peopleReportsService.getAttendanceDiscrepancyReport())
                                                                                                                                .thenReturn(new AttendanceDiscrepancyReportResponse(
                                                                                                                                                                                                Instant.parse("2026-02-17T10:00:00Z"),
                                                                                                                                                                                                12,
                                                                                                                                                                                                3,
                                                                                                                                                                                                2));

                                                                mockMvc.perform(withAuth(get("/v1/people/reports/attendanceJobtimeDiscrepancy")))
                                                                                                                                .andExpect(status().isOk())
                                                                                                                                .andExpect(jsonPath("$.approvedCount").value(12))
                                                                                                                                .andExpect(jsonPath("$.pendingApprovalCount").value(3))
                                                                                                                                .andExpect(jsonPath("$.rejectedCount").value(2));
                                }

                                @Test
                                @DisplayName("happy path: approve time entries returns decision results")
                                void approveTimeEntries_happyPath() throws Exception {
                                                                when(timeEntryService.approveEntries(anyList(), any(), any(), any()))
                                                                                                                                .thenReturn(List.of(new TimeEntryDecisionResult(
                                                                                                                                                                                                "11111111-1111-1111-1111-111111111111",
                                                                                                                                                                                                true,
                                                                                                                                                                                                null,
                                                                                                                                                                                                null)));

                                                                String payload = """
                                                                                                                                {
                                                                                                                                        "decisions": [
                                                                                                                                                {
                                                                                                                                                        "timeEntryId": "11111111-1111-1111-1111-111111111111"
                                                                                                                                                }
                                                                                                                                        ]
                                                                                                                                }
                                                                                                                                """;

                                                                mockMvc.perform(withAuth(post("/v1/people/timeEntries/approve")
                                                                                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                                                                                .content(payload)
                                                                                                                                .header("X-User-Id", "approver-user")
                                                                                                                                .header("X-Permissions", "people:timeEntry:approve")))
                                                                                                                                .andExpect(status().isOk())
                                                                                                                                .andExpect(jsonPath("$.results[0].timeEntryId")
                                                                                                                                                                                                .value("11111111-1111-1111-1111-111111111111"))
                                                                                                                                .andExpect(jsonPath("$.results[0].success").value(true));
                                }

                                @Test
                                @DisplayName("validation: reject time entries requires non-empty decisions")
                                void rejectTimeEntries_rejectsEmptyDecisions() throws Exception {
                                                                String payload = """
                                                                                                                                {
                                                                                                                                        "decisions": []
                                                                                                                                }
                                                                                                                                """;

                                                                mockMvc.perform(withAuth(post("/v1/people/timeEntries/reject")
                                                                                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                                                                                .content(payload)))
                                                                                                                                .andExpect(status().isBadRequest())
                                                                                                                                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
                                }

                                @Test
                                @DisplayName("validation: reject time entries requires rejectionReason for all decisions")
                                void rejectTimeEntries_requiresReason() throws Exception {
                                                                String payload = """
                                                                                                                                {
                                                                                                                                        "decisions": [
                                                                                                                                                {
                                                                                                                                                        "timeEntryId": "11111111-1111-1111-1111-111111111111"
                                                                                                                                                }
                                                                                                                                        ]
                                                                                                                                }
                                                                                                                                """;

                                                                mockMvc.perform(withAuth(post("/v1/people/timeEntries/reject")
                                                                                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                                                                                .content(payload)))
                                                                                                                                .andExpect(status().isBadRequest())
                                                                                                                                .andExpect(jsonPath("$.errorCode").value("REJECTION_REASON_REQUIRED"))
                                                                                                                                .andExpect(jsonPath("$.message").value("rejectionReason is required for all decisions"));
                                }

                                @Test
                                @DisplayName("happy path: reject time entries returns decision results")
                                void rejectTimeEntries_happyPath() throws Exception {
                                                                when(timeEntryService.rejectEntries(anyList(), any(), any(), any(), any()))
                                                                                                                                .thenReturn(List.of(new TimeEntryDecisionResult(
                                                                                                                                                                                                "11111111-1111-1111-1111-111111111111",
                                                                                                                                                                                                true,
                                                                                                                                                                                                null,
                                                                                                                                                                                                null)));

                                                                String payload = """
                                                                                                                                {
                                                                                                                                        "decisions": [
                                                                                                                                                {
                                                                                                                                                        "timeEntryId": "11111111-1111-1111-1111-111111111111",
                                                                                                                                                        "rejectionReason": "Policy mismatch"
                                                                                                                                                }
                                                                                                                                        ]
                                                                                                                                }
                                                                                                                                """;

                                                                mockMvc.perform(withAuth(post("/v1/people/timeEntries/reject")
                                                                                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                                                                                .content(payload)
                                                                                                                                .header("X-User-Id", "rejector-user")
                                                                                                                                .header("X-Permissions", "people:timeEntry:reject")))
                                                                                                                                .andExpect(status().isOk())
                                                                                                                                .andExpect(jsonPath("$.results[0].success").value(true));
                                }

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

                when(peopleAccessControlService.assignRoleToPerson(eq(personUuid), eq("SHOP_MANAGER"), any(), any(),
                                any()))
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

                when(peopleAccessControlService.assignRoleToPerson(eq(personUuid), eq("SHOP_MANAGER"), any(), any(),
                                any()))
                                .thenThrow(new IllegalArgumentException(
                                                "endDate must be greater than or equal to startDate"));

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
                                .andExpect(jsonPath("$.detail")
                                                .value("endDate must be greater than or equal to startDate"));
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
                                .thenThrow(new SecurityServiceException("Overlapping assignments are not allowed",
                                                409));

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

                mockMvc.perform(withAuth(
                                delete("/v1/people/{personUuid}/access/assignments/{roleCode}", personUuid, roleCode)
                                                .param("endDate", "2026-12-31T23:59:59")))
                                .andExpect(status().isNoContent());
        }

}

        