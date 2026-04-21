package com.positivity.customer.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.customer.config.WebMvcTestSecurityConfig;
import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CreatePersonResponse;
import com.positivity.customer.internal.dto.CustomerBulkIngestRecord;
import com.positivity.customer.internal.enums.ContactPointType;
import com.positivity.customer.service.PersonService;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(CustomerBulkIngestController.class)
@Import(WebMvcTestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({ "java:S6813", "java:S100", "java:S1192" })
class CustomerBulkIngestControllerTest {

  private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
  private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
  private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");

  @Autowired
  MockMvc mockMvc;

  @Autowired
  ObjectMapper objectMapper;

  @MockitoBean
  java.time.Clock clock;

  @MockitoBean
  PersonService personService;

  // ─── POST /v1/customer/bulk-ingest — 200 OK ──────────────────────────────

  @Test
  void bulkIngest_returnsOkWithResults_whenAllRecordsSucceed() throws Exception {
    CustomerBulkIngestRecord ingestRecord = new CustomerBulkIngestRecord();
    ingestRecord.setFirstName("Jane");
    ingestRecord.setLastName("Doe");
    ingestRecord.setEmail("jane@example.com");

    BulkIngestRequest<CustomerBulkIngestRecord> request = new BulkIngestRequest<>();
    request.setJobId(JOB_ID);
    request.setLocationId(LOCATION_ID);
    request.setRecords(List.of(ingestRecord));

    CreatePersonResponse personResponse = CreatePersonResponse.builder()
        .personId(PERSON_ID)
        .build();

    when(personService.createPerson(any(), any())).thenReturn(personResponse);

    mockMvc.perform(post("/v1/customer/bulk-ingest")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalSubmitted").value(1))
        .andExpect(jsonPath("$.successCount").value(1))
        .andExpect(jsonPath("$.failureCount").value(0));
  }

  @Test
  void bulkIngest_returnsPartialFailure_whenSomeRecordsFail() throws Exception {
    CustomerBulkIngestRecord ingestRecord = new CustomerBulkIngestRecord();
    ingestRecord.setFirstName("Bad");
    ingestRecord.setLastName("Customer");

    BulkIngestRequest<CustomerBulkIngestRecord> request = new BulkIngestRequest<>();
    request.setJobId(JOB_ID);
    request.setLocationId(LOCATION_ID);
    request.setRecords(List.of(ingestRecord));

    when(personService.createPerson(any(), any()))
        .thenThrow(new IllegalArgumentException("Duplicate customer"));

    mockMvc.perform(post("/v1/customer/bulk-ingest")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalSubmitted").value(1))
        .andExpect(jsonPath("$.successCount").value(0))
        .andExpect(jsonPath("$.failureCount").value(1))
        .andExpect(jsonPath("$.results[0].errorCode").value("CUSTOMER_INGEST_FAILED"));
  }

  @Test
  void bulkIngest_mapsEmailAndPhoneToCreatePersonRequest() throws Exception {
    CustomerBulkIngestRecord ingestRecord = new CustomerBulkIngestRecord();
    ingestRecord.setFirstName("Alice");
    ingestRecord.setLastName("Smith");
    ingestRecord.setEmail("alice@example.com");
    ingestRecord.setPhoneNumber("+15550001111");

    BulkIngestRequest<CustomerBulkIngestRecord> request = new BulkIngestRequest<>();
    request.setJobId(JOB_ID);
    request.setLocationId(LOCATION_ID);
    request.setRecords(List.of(ingestRecord));

    CreatePersonResponse personResponse = CreatePersonResponse.builder()
        .personId(PERSON_ID)
        .build();

    ArgumentCaptor<CreatePersonRequest> captor = ArgumentCaptor.forClass(CreatePersonRequest.class);
    when(personService.createPerson(any(), any())).thenReturn(personResponse);

    mockMvc.perform(post("/v1/customer/bulk-ingest")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk());

    verify(personService).createPerson(captor.capture(), any());
    CreatePersonRequest captured = captor.getValue();

    assertThat(captured.getEmails()).isNotNull().isNotEmpty();
    assertThat(captured.getEmails().get(0).getValue()).isEqualTo("alice@example.com");
    assertThat(captured.getEmails().get(0).isPrimary()).isTrue();

    assertThat(captured.getPhones()).isNotNull().isNotEmpty();
    assertThat(captured.getPhones().get(0).getValue()).isEqualTo("+15550001111");
    assertThat(captured.getPhones().get(0).getType()).isEqualTo(ContactPointType.PHONE_MOBILE);
    assertThat(captured.getPhones().get(0).isPrimary()).isTrue();
  }
}
