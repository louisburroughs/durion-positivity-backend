package com.positivity.customer.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.customer.config.WebMvcTestSecurityConfig;
import com.positivity.customer.internal.dto.CommercialBulkIngestRecord;
import com.positivity.customer.internal.dto.CreateCommercialAccountRequest;
import com.positivity.customer.internal.dto.CreateCommercialAccountResponse;
import com.positivity.customer.internal.dto.CreatePartyRelationshipRequest;
import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CreatePersonResponse;
import com.positivity.customer.internal.enums.PartyRelationshipRole;
import com.positivity.customer.internal.service.PartyRelationshipService;
import com.positivity.customer.internal.service.PartyService;
import com.positivity.customer.internal.service.PersonService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(CommercialBulkIngestController.class)
@Import(WebMvcTestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class CommercialBulkIngestControllerTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final UUID PARTY_ID = UUID.fromString("00000000-0000-0000-0000-000000000022");
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-000000000023");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-26T12:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    PartyService partyService;

    @MockitoBean
    PersonService personService;

    @MockitoBean
    PartyRelationshipService partyRelationshipService;

    private BulkIngestRequest<CommercialBulkIngestRecord> requestOf(CommercialBulkIngestRecord... records) {
        BulkIngestRequest<CommercialBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(records));
        return request;
    }

    private CreateCommercialAccountResponse accountResponse() {
        return CreateCommercialAccountResponse.builder()
                .partyId(PARTY_ID.toString())
                .legalName("Piedmont Freight Carriers LLC")
                .status("ACTIVE")
                .customerNumber("CUST-00000042")
                .build();
    }

    // ─── POST /v1/customer/commercial/bulk-ingest — 200 OK ───────────────────

    @Test
    void bulkIngest_accountOnly_createsPartyAndDefaultsDisplayName() throws Exception {
        CommercialBulkIngestRecord ingestRecord = new CommercialBulkIngestRecord();
        ingestRecord.setLegalName("Piedmont Freight Carriers LLC");

        when(partyService.createCommercialAccount(any())).thenReturn(accountResponse());

        mockMvc.perform(post("/v1/customer/commercial/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestOf(ingestRecord))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(1))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0))
                .andExpect(jsonPath("$.results[0].entityId").value(PARTY_ID.toString()));

        ArgumentCaptor<CreateCommercialAccountRequest> captor =
                ArgumentCaptor.forClass(CreateCommercialAccountRequest.class);
        verify(partyService).createCommercialAccount(captor.capture());
        assertThat(captor.getValue().getLegalName()).isEqualTo("Piedmont Freight Carriers LLC");
        assertThat(captor.getValue().getDisplayName()).isEqualTo("Piedmont Freight Carriers LLC");
        verifyNoInteractions(personService, partyRelationshipService);
    }

    @Test
    void bulkIngest_withContact_createsPersonAndPrimaryContactRelationship() throws Exception {
        when(clock.instant()).thenReturn(FIXED_INSTANT);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        CommercialBulkIngestRecord ingestRecord = new CommercialBulkIngestRecord();
        ingestRecord.setLegalName("Piedmont Freight Carriers LLC");
        ingestRecord.setDisplayName("Piedmont Freight");
        ingestRecord.setContactFirstName("Dale");
        ingestRecord.setContactLastName("Whitfield");
        ingestRecord.setContactEmail("dale@piedmont.example.com");

        when(partyService.createCommercialAccount(any())).thenReturn(accountResponse());
        when(personService.createPerson(any(), any()))
                .thenReturn(CreatePersonResponse.builder().personId(PERSON_ID).build());

        BulkIngestRequest<CommercialBulkIngestRecord> request = requestOf(ingestRecord);
        request.setOperatorId("00000000-0000-0000-0000-000000000099");

        mockMvc.perform(post("/v1/customer/commercial/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1));

        ArgumentCaptor<CreatePersonRequest> personCaptor = ArgumentCaptor.forClass(CreatePersonRequest.class);
        ArgumentCaptor<UUID> userIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(personService).createPerson(personCaptor.capture(), userIdCaptor.capture());
        assertThat(personCaptor.getValue().getFirstName()).isEqualTo("Dale");
        assertThat(personCaptor.getValue().getLastName()).isEqualTo("Whitfield");
        assertThat(personCaptor.getValue().getEmails()).hasSize(1);
        assertThat(personCaptor.getValue().getEmails().get(0).getValue()).isEqualTo("dale@piedmont.example.com");
        assertThat(userIdCaptor.getValue()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000099"));

        ArgumentCaptor<CreatePartyRelationshipRequest> relationshipCaptor =
                ArgumentCaptor.forClass(CreatePartyRelationshipRequest.class);
        verify(partyRelationshipService).createRelationship(eq(PARTY_ID), relationshipCaptor.capture(), any());
        assertThat(relationshipCaptor.getValue().getPersonId()).isEqualTo(PERSON_ID);
        assertThat(relationshipCaptor.getValue().getRoles()).containsExactly(PartyRelationshipRole.PRIMARY_CONTACT);
        assertThat(relationshipCaptor.getValue().getEffectiveStartDate()).isEqualTo(LocalDate.of(2026, 8, 26));
    }

    @Test
    void bulkIngest_whenAccountCreationThrows_recordsFailureAndContinues() throws Exception {
        CommercialBulkIngestRecord failing = new CommercialBulkIngestRecord();
        failing.setLegalName("Broken Account LLC");
        CommercialBulkIngestRecord succeeding = new CommercialBulkIngestRecord();
        succeeding.setLegalName("Piedmont Freight Carriers LLC");

        when(partyService.createCommercialAccount(any()))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "bad"))
                .thenReturn(accountResponse());

        mockMvc.perform(post("/v1/customer/commercial/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestOf(failing, succeeding))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(2))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("COMMERCIAL_INGEST_FAILED"))
                .andExpect(jsonPath("$.results[0].errorMessage").value("bad"));
    }

    /**
     * Issue #1718: the contact leg of a commercial row failing for a server-side reason must not
     * put that exception's text in the 200 body. Only the correlation id crosses the wire.
     */
    @Test
    void bulkIngest_whenContactAttachThrows_reportsGenericFailureAndTheCorrelationId() throws Exception {
        CommercialBulkIngestRecord ingestRecord = new CommercialBulkIngestRecord();
        ingestRecord.setLegalName("Piedmont Freight Carriers LLC");
        ingestRecord.setContactFirstName("Dale");
        ingestRecord.setContactLastName("Whitfield");

        when(partyService.createCommercialAccount(any())).thenReturn(accountResponse());
        when(personService.createPerson(any(), any()))
                .thenThrow(new IllegalStateException("could not execute statement [insert into crm_person ...]"));

        mockMvc.perform(post("/v1/customer/commercial/bulk-ingest")
                        .header("X-Correlation-Id", "corr-from-caller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestOf(ingestRecord))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.results[0].correlationId").value("corr-from-caller"))
                .andExpect(jsonPath("$.results[0].errorMessage", not(containsString("crm_person"))));
    }

    @Test
    void bulkIngest_missingLegalName_returns400() throws Exception {
        CommercialBulkIngestRecord ingestRecord = new CommercialBulkIngestRecord();
        ingestRecord.setDisplayName("No Legal Name");

        mockMvc.perform(post("/v1/customer/commercial/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestOf(ingestRecord))))
                .andExpect(status().isBadRequest());
    }
}
