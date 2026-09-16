package com.positivity.people.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.people.config.TestSecurityConfig;
import com.positivity.people.internal.dto.CredentialUpsertCommand;
import com.positivity.people.internal.dto.EmployeeIdentityDto;
import com.positivity.people.internal.dto.PersonCredentialBulkIngestRecord;
import com.positivity.people.internal.dto.PersonCredentialResponse;
import com.positivity.people.internal.exception.UnknownSkillCodeException;
import com.positivity.people.internal.service.EmployeeService;
import com.positivity.people.internal.service.PersonCredentialService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;

/** The credential ingest over HTTP (CAP-328): resolution, rejection codes, supersession switch. */
@WebMvcTest(PersonCredentialBulkIngestController.class)
@Import({TestSecurityConfig.class, PersonCredentialBulkIngestControllerTest.FixedClockConfig.class})
@ActiveProfiles("test")
class PersonCredentialBulkIngestControllerTest {

    private static final String PATH = "/v1/people/credentials/bulk-ingest";
    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000070");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000071");
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-000000000072");
    private static final UUID CREDENTIAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000073");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    PersonCredentialService personCredentialService;

    @MockitoBean
    EmployeeService employeeService;

    private static PersonCredentialBulkIngestRecord ase(String employeeNumber, String code) {
        PersonCredentialBulkIngestRecord record = new PersonCredentialBulkIngestRecord();
        record.setEmployeeNumber(employeeNumber);
        record.setSourceCode("ASE");
        record.setSourceCredentialCode(code);
        record.setIssuedOn(LocalDate.of(2024, 3, 15));
        record.setExpiresOn(LocalDate.of(2029, 3, 15));
        record.setProficiency(4);
        return record;
    }

    private String body(List<PersonCredentialBulkIngestRecord> records) throws Exception {
        BulkIngestRequest<PersonCredentialBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setOperatorId("seed-operator");
        request.setRecords(records);
        return objectMapper.writeValueAsString(request);
    }

    private void employeeExists(String number) {
        when(employeeService.resolveByEmployeeNumber(number))
                .thenReturn(Optional.of(EmployeeIdentityDto.builder().personId(PERSON_ID).employeeNumber(number).build()));
    }

    @Test
    void bulkIngest_resolvesTheEmployeeAndWritesTheCredential() throws Exception {
        employeeExists("EMP-0006");
        when(personCredentialService.upsert(eq(PERSON_ID), any(), anyString()))
                .thenReturn(PersonCredentialResponse.builder().credentialId(CREDENTIAL_ID).build());

        mockMvc.perform(post(PATH)
                        .header("X-Authorities", "people:employee:edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(ase("EMP-0006", "T4-BRAKES")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.results[0].entityId").value(CREDENTIAL_ID.toString()));

        ArgumentCaptor<CredentialUpsertCommand> captor = ArgumentCaptor.forClass(CredentialUpsertCommand.class);
        verify(personCredentialService).upsert(eq(PERSON_ID), captor.capture(), eq("seed-operator"));
        assertThat(captor.getValue().getSourceCredentialCode()).isEqualTo("T4-BRAKES");
        assertThat(captor.getValue().getSourceSystem()).isEqualTo("bulk-ingest:" + JOB_ID);
        verify(personCredentialService, never()).supersedeAbsent(any(), any(), any(), any());
    }

    @Test
    void bulkIngest_unknownEmployee_isARowFailureWithItsOwnCode() throws Exception {
        when(employeeService.resolveByEmployeeNumber("EMP-9999")).thenReturn(Optional.empty());

        mockMvc.perform(post(PATH)
                        .header("X-Authorities", "people:employee:edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(ase("EMP-9999", "T4-BRAKES")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("CREDENTIAL_EMPLOYEE_UNKNOWN"));
        verify(personCredentialService, never()).upsert(any(), any(), any());
    }

    @Test
    void bulkIngest_unknownVendorCode_rejectsTheRowLoudly_andTheRestProceed() throws Exception {
        employeeExists("EMP-0006");
        when(personCredentialService.upsert(eq(PERSON_ID), any(), anyString()))
                .thenThrow(new UnknownSkillCodeException("ASE", "T3-ALIGN"))
                .thenReturn(PersonCredentialResponse.builder().credentialId(CREDENTIAL_ID).build());

        mockMvc.perform(post(PATH)
                        .header("X-Authorities", "people:employee:edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(ase("EMP-0006", "T3-ALIGN"), ase("EMP-0006", "T4-BRAKES")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("CREDENTIAL_INGEST_REJECTED"))
                .andExpect(jsonPath("$.results[0].errorMessage").value(org.hamcrest.Matchers.containsString("T3-ALIGN")))
                .andExpect(jsonPath("$.results[1].success").value(true));
    }

    @Test
    void bulkIngest_withSupersedeAbsent_supersedesThisSourcesRowsTheBatchNoLongerLists() throws Exception {
        employeeExists("EMP-0006");
        when(personCredentialService.upsert(eq(PERSON_ID), any(), anyString()))
                .thenReturn(PersonCredentialResponse.builder().credentialId(CREDENTIAL_ID).build());

        mockMvc.perform(post(PATH + "?supersedeAbsent=true")
                        .header("X-Authorities", "people:employee:edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(ase("EMP-0006", "T4-BRAKES")))))
                .andExpect(status().isOk());

        verify(personCredentialService)
                .supersedeAbsent(PERSON_ID, "bulk-ingest:" + JOB_ID, Set.of(CREDENTIAL_ID), "bulk-ingest:" + JOB_ID);
    }

    @Test
    void bulkIngest_withoutEmployeeEdit_isForbidden() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Authorities", "people:employee:view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(ase("EMP-0006", "T4-BRAKES")))))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
