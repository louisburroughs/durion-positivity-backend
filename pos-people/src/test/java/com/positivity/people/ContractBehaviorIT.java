package com.positivity.people;

import com.positivity.people.internal.client.SecurityServiceException;
import com.positivity.people.internal.client.WorkexecClientException;
import com.positivity.people.internal.client.WorkexecJobTimeClient;
import com.positivity.people.internal.client.LocationReferenceClient;
import com.positivity.people.internal.client.dto.WorkexecJobTimeTotal;
import com.positivity.people.internal.dto.TimeEntryDecisionResult;
import com.positivity.people.internal.client.dto.UserRoleDto;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.entity.TimekeepingPolicy;
import com.positivity.people.internal.enums.TimekeepingPolicyScopeType;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.internal.repository.TimekeepingPolicyRepository;
import com.positivity.people.service.PeopleAccessControlService;
import com.positivity.people.service.TimeEntryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
class ContractBehaviorIT extends BaseContractIntegrationTest {

	@MockitoBean
	private PeopleAccessControlService peopleAccessControlService;

	@MockitoBean
	private TimeEntryService timeEntryService;

	@MockitoBean
	private WorkexecJobTimeClient workexecJobTimeClient;

	@MockitoBean
	private LocationReferenceClient locationReferenceClient;

	@Autowired
	private TimeEntryRepository timeEntryRepository;

	@Autowired
	private TimekeepingPolicyRepository timekeepingPolicyRepository;

	@Autowired
	private PersonRepository personRepository;

	private UUID technicianId;

	private UUID locationId;

	@BeforeEach
	void beforeTest() {
		technicianId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		locationId = UUID.fromString("22222222-2222-2222-2222-222222222222");
	}

	@Test
	@DisplayName("happy path: discrepancy above threshold is flagged")
	void attendanceDiscrepancyReport_happyPath() throws Exception {
		resetReportData();
		LocalDate reportDate = LocalDate.parse("2026-02-16");

		seedTechnician(technicianId, "Jane", "Doe");
		seedAttendance(technicianId, locationId, Instant.parse("2026-02-16T08:00:00Z"),
				Instant.parse("2026-02-16T16:00:00Z"));
		seedGlobalThreshold(60);

		when(workexecJobTimeClient.getJobTimeTotals(reportDate, reportDate, "UTC", locationId, List.of(technicianId)))
			.thenReturn(List.of(jobTime(technicianId, locationId, reportDate, 360)));

		mockMvc
			.perform(withAuth(get("/v1/people/reports/attendanceJobtimeDiscrepancy").param("startDate", "2026-02-16")
				.param("endDate", "2026-02-16")
				.param("timezone", "UTC")
				.param("locationId", locationId.toString())
				.param("technicianIds", technicianId.toString())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].technicianId").value(technicianId.toString()))
			.andExpect(jsonPath("$[0].technicianName").value("Jane Doe"))
			.andExpect(jsonPath("$[0].totalAttendanceHours").value(8.0))
			.andExpect(jsonPath("$[0].totalJobHours").value(6.0))
			.andExpect(jsonPath("$[0].discrepancyHours").value(2.0))
			.andExpect(jsonPath("$[0].thresholdApplied").value(60))
			.andExpect(jsonPath("$[0].isFlagged").value(true));
	}

	@Test
	@DisplayName("concurrency invariant: strict threshold comparison uses greater-than")
	void attendanceDiscrepancyReport_strictThresholdInvariant() throws Exception {
		resetReportData();
		UUID strictTechnicianId = UUID.fromString("33333333-3333-3333-3333-333333333333");
		UUID strictLocationId = UUID.fromString("44444444-4444-4444-4444-444444444444");
		LocalDate reportDate = LocalDate.parse("2026-02-17");

		seedTechnician(strictTechnicianId, "Alex", "Kim");
		seedAttendance(strictTechnicianId, strictLocationId, Instant.parse("2026-02-17T08:00:00Z"),
				Instant.parse("2026-02-17T16:00:00Z"));
		seedGlobalThreshold(30);
		seedLocationThreshold(strictLocationId, 60);

		when(workexecJobTimeClient.getJobTimeTotals(reportDate, reportDate, "UTC", strictLocationId,
				List.of(strictTechnicianId)))
			.thenReturn(List.of(jobTime(strictTechnicianId, strictLocationId, reportDate, 435)));

		mockMvc
			.perform(withAuth(get("/v1/people/reports/attendanceJobtimeDiscrepancy").param("startDate", "2026-02-17")
				.param("endDate", "2026-02-17")
				.param("timezone", "UTC")
				.param("locationId", strictLocationId.toString())
				.param("technicianIds", strictTechnicianId.toString())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].thresholdApplied").value(60))
			.andExpect(jsonPath("$[0].discrepancyHours").value(0.75))
			.andExpect(jsonPath("$[0].isFlagged").value(false));
	}

	@Test
	@DisplayName("validation: invalid timezone returns 400")
	void attendanceDiscrepancyReport_rejectsInvalidTimezone() throws Exception {
		mockMvc
			.perform(withAuth(get("/v1/people/reports/attendanceJobtimeDiscrepancy").param("startDate", "2026-02-17")
				.param("endDate", "2026-02-17")
				.param("timezone", "Not/AZone")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("timezone must be a valid IANA timezone"));
	}

	@Test
	@DisplayName("auth failure: unauthenticated report request is rejected")
	void attendanceDiscrepancyReport_unauthenticatedRejected() throws Exception {
		mockMvc
			.perform(get("/v1/people/reports/attendanceJobtimeDiscrepancy").param("startDate", "2026-02-17")
				.param("endDate", "2026-02-17")
				.param("timezone", "UTC"))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("dependency failure: workexec non-2xx fails request")
	void attendanceDiscrepancyReport_workexecFailure() throws Exception {
		when(workexecJobTimeClient.getJobTimeTotals(any(), any(), any(), any(), any()))
			.thenThrow(new WorkexecClientException("Work Execution request failed with status 503", 503,
					"WORKEXEC_UNAVAILABLE"));

		mockMvc
			.perform(withAuth(get("/v1/people/reports/attendanceJobtimeDiscrepancy").param("startDate", "2026-02-17")
				.param("endDate", "2026-02-17")
				.param("timezone", "UTC")))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.errorCode").value("WORKEXEC_UNAVAILABLE"));
	}

	@Test
	@DisplayName("happy path: approved time export rows are returned")
	void approvedTimeExport_happyPath() throws Exception {
		resetReportData();
		LocalDate reportDate = LocalDate.parse("2026-02-16");

		seedTechnician(technicianId, "Jane", "Doe");

		TimeEntry approved = new TimeEntry();
		approved.setPersonId(technicianId.toString());
		approved.setLocationId(locationId);
		approved.setStatus(com.positivity.people.internal.enums.TimeEntryStatus.APPROVED);
		approved.setAttendanceStartAt(Instant.parse("2026-02-16T08:00:00Z"));
		approved.setAttendanceEndAt(Instant.parse("2026-02-16T16:30:00Z"));
		approved.setApprovedAt(Instant.parse("2026-02-16T17:00:00Z"));
		approved.setApprovedBy("manager-1");
		timeEntryRepository.save(approved);

		when(locationReferenceClient.isLocationActive(locationId)).thenReturn(true);
		when(locationReferenceClient.getLocationName(locationId)).thenReturn("North Shop");

		mockMvc
			.perform(withAuth(get("/v1/people/reports/approvedTime").param("startDate", reportDate.toString())
				.param("endDate", reportDate.toString())
				.param("locationId", locationId.toString())
				.header("X-Authorities", "people:time:export:read")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].employeeId").value(technicianId.toString()))
			.andExpect(jsonPath("$[0].employeeName").value("Jane Doe"))
			.andExpect(jsonPath("$[0].locationName").value("North Shop"))
			.andExpect(jsonPath("$[0].hoursWorked").value(8.50));
	}

	@Test
	@DisplayName("validation: approved time export rejects invalid date range")
	void approvedTimeExport_invalidDateRange() throws Exception {
		mockMvc
			.perform(withAuth(get("/v1/people/reports/approvedTime").param("startDate", "2026-02-17")
				.param("endDate", "2026-02-16")
				.param("locationId", locationId.toString())
				.header("X-Authorities", "people:time:export:read")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("endDate must be on or after startDate"));
	}

	@Test
	@DisplayName("auth failure: approved time export requires export authority")
	void approvedTimeExport_forbiddenWithoutAuthority() throws Exception {
		mockMvc
			.perform(withAuth(get("/v1/people/reports/approvedTime").param("startDate", "2026-02-16")
				.param("endDate", "2026-02-16")
				.param("locationId", locationId.toString())))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("dependency failure: approved time export propagates 503")
	void approvedTimeExport_dependencyFailure() throws Exception {
		when(locationReferenceClient.isLocationActive(locationId)).thenReturn(true);
		when(locationReferenceClient.getLocationName(locationId))
			.thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to fetch location"));

		TimeEntry approved = new TimeEntry();
		approved.setPersonId(technicianId.toString());
		approved.setLocationId(locationId);
		approved.setStatus(com.positivity.people.internal.enums.TimeEntryStatus.APPROVED);
		approved.setAttendanceStartAt(Instant.parse("2026-02-16T08:00:00Z"));
		approved.setAttendanceEndAt(Instant.parse("2026-02-16T16:00:00Z"));
		approved.setApprovedAt(Instant.parse("2026-02-16T17:00:00Z"));
		approved.setApprovedBy("manager-1");
		timeEntryRepository.save(approved);

		mockMvc
			.perform(withAuth(get("/v1/people/reports/approvedTime").param("startDate", "2026-02-16")
				.param("endDate", "2026-02-16")
				.param("locationId", locationId.toString())
				.header("X-Authorities", "people:time:export:read")))
			.andExpect(status().isServiceUnavailable());
	}

	@Test
	@DisplayName("happy path: approve time entries returns decision results")
	void approveTimeEntries_happyPath() throws Exception {
		when(timeEntryService.approveEntries(anyList(), any(), any(), any()))
			.thenReturn(List.of(new TimeEntryDecisionResult("11111111-1111-1111-1111-111111111111", true, null, null)));

		String payload = """
				{
				        "decisions": [
				                {
				                        "timeEntryId": "11111111-1111-1111-1111-111111111111"
				                }
				        ]
				}
				""";

		mockMvc
			.perform(withAuth(post("/v1/people/timeEntries/approve").contentType(MediaType.APPLICATION_JSON)
				.content(payload)
				.header("X-User-Id", "approver-user")
				.header("X-Permissions", "people:timeEntry:approve")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.results[0].timeEntryId").value("11111111-1111-1111-1111-111111111111"))
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

		mockMvc
			.perform(withAuth(
					post("/v1/people/timeEntries/reject").contentType(MediaType.APPLICATION_JSON).content(payload)))
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

		mockMvc
			.perform(withAuth(
					post("/v1/people/timeEntries/reject").contentType(MediaType.APPLICATION_JSON).content(payload)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("REJECTION_REASON_REQUIRED"))
			.andExpect(jsonPath("$.message").value("rejectionReason is required for all decisions"));
	}

	@Test
	@DisplayName("happy path: reject time entries returns decision results")
	void rejectTimeEntries_happyPath() throws Exception {
		when(timeEntryService.rejectEntries(anyList(), any(), any(), any(), any()))
			.thenReturn(List.of(new TimeEntryDecisionResult("11111111-1111-1111-1111-111111111111", true, null, null)));

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

		mockMvc
			.perform(withAuth(post("/v1/people/timeEntries/reject").contentType(MediaType.APPLICATION_JSON)
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

		mockMvc
			.perform(withAuth(
					get("/v1/people/{personUuid}/access/assignments", personUuid).param("includeHistory", "true")))
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

		mockMvc
			.perform(withAuth(post("/v1/people/{personUuid}/access/assignments", personUuid)
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

		mockMvc
			.perform(withAuth(post("/v1/people/{personUuid}/access/assignments", personUuid)
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

		mockMvc
			.perform(withAuth(post("/v1/people/{personUuid}/access/assignments", personUuid)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("endDate must be greater than or equal to startDate"));
	}

	@Test
	@DisplayName("auth failure: unauthenticated request is rejected")
	void createAssignment_unauthenticatedRejected() throws Exception {
		UUID personUuid = UUID.randomUUID();

		mockMvc
			.perform(post("/v1/people/{personUuid}/access/assignments", personUuid)
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

		mockMvc
			.perform(withAuth(post("/v1/people/{personUuid}/access/assignments", personUuid)
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

		mockMvc
			.perform(withAuth(delete("/v1/people/{personUuid}/access/assignments/{roleCode}", personUuid, roleCode)
				.param("endDate", "2026-12-31T23:59:59")))
			.andExpect(status().isNoContent());
	}

	private void seedTechnician(UUID technicianId, String firstName, String lastName) {
		Person person = Person.builder()
			.id(technicianId)
			.firstName(firstName)
			.lastName(lastName)
			.primaryEmail(firstName.toLowerCase() + "@example.com")
			.phoneNumbers(new ArrayList<>())
			.build();
		personRepository.save(person);
	}

	private void seedAttendance(UUID technicianId, UUID locationId, Instant attendanceStartAt,
			Instant attendanceEndAt) {
		TimeEntry entry = new TimeEntry();
		entry.setPersonId(technicianId.toString());
		entry.setLocationId(locationId);
		entry.setAttendanceStartAt(attendanceStartAt);
		entry.setAttendanceEndAt(attendanceEndAt);
		timeEntryRepository.save(entry);
	}

	private void seedGlobalThreshold(int thresholdMinutes) {
		TimekeepingPolicy policy = new TimekeepingPolicy();
		policy.setScopeType(TimekeepingPolicyScopeType.GLOBAL);
		policy.setJobTimeDiscrepancyThresholdMinutes(thresholdMinutes);
		policy.setUpdatedBy("test");
		policy.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
		timekeepingPolicyRepository.save(policy);
	}

	private void seedLocationThreshold(UUID locationId, int thresholdMinutes) {
		TimekeepingPolicy policy = new TimekeepingPolicy();
		policy.setScopeType(TimekeepingPolicyScopeType.LOCATION);
		policy.setScopeId(locationId);
		policy.setJobTimeDiscrepancyThresholdMinutes(thresholdMinutes);
		policy.setUpdatedBy("test");
		policy.setUpdatedAt(Instant.parse("2026-01-02T00:00:00Z"));
		timekeepingPolicyRepository.save(policy);
	}

	private WorkexecJobTimeTotal jobTime(UUID technicianId, UUID locationId, LocalDate localDate, int minutes) {
		WorkexecJobTimeTotal row = new WorkexecJobTimeTotal();
		row.setTechnicianId(technicianId);
		row.setLocationId(locationId);
		row.setLocalDate(localDate);
		row.setTotalJobMinutes(minutes);
		return row;
	}

	private void resetReportData() {
		timeEntryRepository.deleteAll();
		timekeepingPolicyRepository.deleteAll();
		personRepository.deleteAll();
	}

}
