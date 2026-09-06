package com.positivity.people.internal.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.people.config.TestSecurityConfig;
import com.positivity.people.internal.dto.EmployeeProfileDto;
import com.positivity.people.internal.dto.PersonBulkIngestRecord;
import com.positivity.people.internal.service.EmployeeService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(PersonBulkIngestController.class)
@Import({TestSecurityConfig.class, PersonBulkIngestControllerTest.FixedClockConfig.class})
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class PersonBulkIngestControllerTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000022");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    EmployeeService employeeService;

    // ─── POST /v1/people/bulk-ingest — 200 OK ────────────────────────────────

    @Test
    void bulkIngest_returnsOkWithResults_whenAllRecordsSucceed() throws Exception {
        PersonBulkIngestRecord ingestRecord = new PersonBulkIngestRecord();
        ingestRecord.setFirstName("Alice");
        ingestRecord.setLastName("Brown");
        ingestRecord.setEmployeeNumber("EMP-001");
        ingestRecord.setHireDate("2025-01-15");

        BulkIngestRequest<PersonBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(ingestRecord));

        EmployeeProfileDto profile = EmployeeProfileDto.builder()
                .id(EMPLOYEE_ID)
                .firstName("Alice")
                .lastName("Brown")
                .employeeNumber("EMP-001")
                .build();

        when(employeeService.createEmployee(any())).thenReturn(profile);

        mockMvc.perform(post("/v1/people/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(1))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0));
    }

    @Test
    void bulkIngest_returnsPartialFailure_whenSomeDatesAreInvalid() throws Exception {
        PersonBulkIngestRecord ingestRecord = new PersonBulkIngestRecord();
        ingestRecord.setFirstName("Bob");
        ingestRecord.setLastName("Jones");
        ingestRecord.setEmployeeNumber("EMP-002");
        ingestRecord.setHireDate("15-Jan-2025"); // invalid ISO format

        BulkIngestRequest<PersonBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(ingestRecord));

        mockMvc.perform(post("/v1/people/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(1))
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("PEOPLE_INGEST_FAILED"))
                .andExpect(jsonPath("$.results[0].errorMessage").value("Invalid hireDate format; expected YYYY-MM-DD"));
    }

    /**
     * Issue #1718: a row lost to a server-side fault must not carry the exception's text into the
     * 200 body that reports it. The caller gets a generic code and their own correlation id.
     */
    @Test
    void bulkIngest_serverFault_reportsGenericFailureAndTheCorrelationId() throws Exception {
        PersonBulkIngestRecord ingestRecord = new PersonBulkIngestRecord();
        ingestRecord.setFirstName("Bob");
        ingestRecord.setLastName("Jones");
        ingestRecord.setEmployeeNumber("EMP-003");
        ingestRecord.setHireDate("2026-01-15");

        BulkIngestRequest<PersonBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(ingestRecord));

        when(employeeService.createEmployee(any()))
                .thenThrow(
                        new IllegalArgumentException("could not execute statement [insert into people_employee ...]"));

        mockMvc.perform(post("/v1/people/bulk-ingest")
                        .header("X-Correlation-Id", "corr-from-caller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("INGEST_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.results[0].errorMessage", containsString("corr-from-caller")))
                .andExpect(jsonPath("$.results[0].errorMessage", not(containsString("people_employee"))));
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
