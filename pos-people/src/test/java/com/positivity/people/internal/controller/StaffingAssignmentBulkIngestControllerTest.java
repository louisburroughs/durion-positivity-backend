package com.positivity.people.internal.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.people.config.TestSecurityConfig;
import com.positivity.people.internal.dto.CreateStaffingAssignmentRequest;
import com.positivity.people.internal.dto.EmployeeIdentityDto;
import com.positivity.people.internal.dto.StaffingAssignmentBulkIngestRecord;
import com.positivity.people.internal.dto.StaffingAssignmentResponse;
import com.positivity.people.internal.service.EmployeeService;
import com.positivity.people.internal.service.StaffingAssignmentService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * The staffing ingest resolves employee numbers to people, which is what lets a roster file be
 * written without knowing ids the pipeline generates.
 */
@WebMvcTest(StaffingAssignmentBulkIngestController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class StaffingAssignmentBulkIngestControllerTest {

    private static final String PATH = "/v1/people/staffing/bulk-ingest";
    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000070");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000071");
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-000000000072");
    private static final UUID ASSIGNMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000073");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    StaffingAssignmentService staffingAssignmentService;

    @MockitoBean
    EmployeeService employeeService;

    @MockitoBean
    Clock clock;

    private void clockIsFixed() {
        when(clock.instant()).thenReturn(Instant.parse("2026-03-01T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private static StaffingAssignmentBulkIngestRecord record(String employeeNumber) {
        StaffingAssignmentBulkIngestRecord record = new StaffingAssignmentBulkIngestRecord();
        record.setEmployeeNumber(employeeNumber);
        record.setRole("TECHNICIAN");
        record.setPrimary(true);
        return record;
    }

    private BulkIngestRequest<StaffingAssignmentBulkIngestRecord> request(
            List<StaffingAssignmentBulkIngestRecord> records) {
        BulkIngestRequest<StaffingAssignmentBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setOperatorId("seed-operator");
        request.setRecords(records);
        return request;
    }

    private static StaffingAssignmentResponse assignmentResponse() {
        StaffingAssignmentResponse response = new StaffingAssignmentResponse();
        response.setAssignmentId(ASSIGNMENT_ID);
        return response;
    }

    private void employeeExists(String number) {
        when(employeeService.resolveByEmployeeNumber(number))
                .thenReturn(Optional.of(EmployeeIdentityDto.builder()
                        .personId(PERSON_ID)
                        .employeeNumber(number)
                        .build()));
        when(staffingAssignmentService.create(any(), anyString())).thenReturn(assignmentResponse());
    }

    @Test
    void bulkIngest_resolvesTheEmployeeNumberToAPerson() throws Exception {
        clockIsFixed();
        employeeExists("EMP-0001");

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(List.of(record("EMP-0001"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.results[0].entityId").value(ASSIGNMENT_ID.toString()));

        verify(staffingAssignmentService)
                .create(
                        org.mockito.ArgumentMatchers.argThat((CreateStaffingAssignmentRequest req) ->
                                PERSON_ID.equals(req.getPersonId()) && LOCATION_ID.equals(req.getLocationId())),
                        org.mockito.ArgumentMatchers.eq("seed-operator"));
    }

    @Test
    void bulkIngest_defaultsEffectiveFromToToday() throws Exception {
        // A fixed date in a checked-in file would eventually be backdated far enough to overlap
        // assignments nobody intended.
        clockIsFixed();
        employeeExists("EMP-0001");

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(List.of(record("EMP-0001"))))))
                .andExpect(status().isOk());

        verify(staffingAssignmentService)
                .create(
                        org.mockito.ArgumentMatchers.argThat((CreateStaffingAssignmentRequest req) ->
                                LocalDate.of(2026, 3, 1).equals(req.getEffectiveFrom())),
                        anyString());
    }

    @Test
    void bulkIngest_unknownEmployeeFailsItsOwnRow() throws Exception {
        when(employeeService.resolveByEmployeeNumber("EMP-9999")).thenReturn(Optional.empty());

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(List.of(record("EMP-9999"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("STAFFING_ASSIGNMENT_EMPLOYEE_UNKNOWN"));

        verify(staffingAssignmentService, never()).create(any(), anyString());
    }

    @Test
    void bulkIngest_resolvesEachEmployeeNumberOnce() throws Exception {
        // A roster commonly lists the same person for several roles.
        clockIsFixed();
        employeeExists("EMP-0001");

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                request(List.of(record("EMP-0001"), record("EMP-0001"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(2));

        verify(employeeService, times(1)).resolveByEmployeeNumber("EMP-0001");
    }

    @Test
    void bulkIngest_anOverlappingAssignmentFailsItsOwnRow() throws Exception {
        clockIsFixed();
        when(employeeService.resolveByEmployeeNumber("EMP-0001"))
                .thenReturn(Optional.of(
                        EmployeeIdentityDto.builder().personId(PERSON_ID).build()));
        // The type the service actually raises, not a stand-in: a rejection has to be
        // recognisable as one for its message to reach the caller (issue #1718).
        when(staffingAssignmentService.create(any(), anyString()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "An overlapping assignment already exists for this person, location, and role"));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(List.of(record("EMP-0001"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("STAFFING_ASSIGNMENT_INGEST_FAILED"))
                .andExpect(jsonPath("$.results[0].errorMessage")
                        .value("An overlapping assignment already exists for this person, location, and role"));
    }

    /**
     * Issue #1718: a row lost to a server-side fault must not carry the exception's text into the
     * 200 body that reports it. The caller gets a generic code and the request's correlation id.
     */
    @Test
    void bulkIngest_serverFault_reportsGenericFailureAndTheCorrelationId() throws Exception {
        clockIsFixed();
        when(employeeService.resolveByEmployeeNumber("EMP-0001"))
                .thenReturn(Optional.of(
                        EmployeeIdentityDto.builder().personId(PERSON_ID).build()));
        when(staffingAssignmentService.create(any(), anyString()))
                .thenThrow(
                        new IllegalStateException("could not execute statement [insert into staffing_assignment ...]"));

        mockMvc.perform(post(PATH)
                        .header("X-Correlation-Id", "corr-from-caller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(List.of(record("EMP-0001"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.results[0].correlationId").value("corr-from-caller"))
                .andExpect(jsonPath("$.results[0].errorMessage", not(containsString("staffing_assignment"))));
    }
}
