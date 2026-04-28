package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.ContractField;
import com.positivity.accounting.internal.dto.EventEnvelopeContract;
import com.positivity.accounting.internal.dto.EventProcessingLogEntry;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.service.EventIngestionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("EventIngestionController Tests")
class EventIngestionControllerTest extends BaseIntegrationTest {

  private static final String BASE_URL = "/v1/accounting/events";
  private static final String VIEW_AUTHORITY = "accounting:events:view";
  private static final String TEST_USER = "test-user";

  private static final UUID EVENT_ID = UUID.fromString("d1234567-89ab-cdef-0123-456789abcdef");

  @MockitoBean
  private EventIngestionService eventIngestionService;

  @Nested
  @DisplayName("GET /v1/accounting/events/contract")
  class GetEventContract {

    @Test
    @DisplayName("should return 200 with contract version when caller has accounting:events:view")
    void getEventContract_returns200() throws Exception {
      EventEnvelopeContract contract = EventEnvelopeContract.builder()
          .version("1.0")
          .fields(List.of(
              ContractField.builder()
                  .name("eventType")
                  .jsonPath("$.eventType")
                  .type("string")
                  .required(true)
                  .description("The type of accounting event")
                  .build()))
          .examples(List.of())
          .build();
      when(eventIngestionService.getEventContract()).thenReturn(contract);

      mockMvc.perform(get(BASE_URL + "/contract")
          .header("X-Authorities", VIEW_AUTHORITY)
          .header("X-User", TEST_USER))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.version").value("1.0"));
    }

    @Test
    @DisplayName("should return 403 when caller lacks accounting:events:view authority")
    void getEventContract_returns403_whenUnauthorized() throws Exception {
      mockMvc.perform(get(BASE_URL + "/contract")
          .header("X-Authorities", "accounting:read")
          .header("X-User", TEST_USER))
          .andExpect(status().isForbidden());
    }
  }

  @Nested
  @DisplayName("GET /v1/accounting/events — all filters optional (B-16)")
  class ListAccountingEvents {

    @Test
    @DisplayName("should return 200 when no query params are provided (all filters optional)")
    void listAccountingEvents_returns200_withAllFiltersOptional() throws Exception {
      AccountingEventResponse eventResponse = AccountingEventResponse.builder()
          .eventId(EVENT_ID)
          .eventType("INVOICE_POSTED")
          .status(AccountingEventStatus.PROCESSED)
          .receivedAt(Instant.parse("2025-06-01T10:00:00Z"))
          .build();
      Page<AccountingEventResponse> page = new PageImpl<>(
          List.of(eventResponse), PageRequest.of(0, 20), 1);
      when(eventIngestionService.listEvents(any(), any(Pageable.class))).thenReturn(page);

      mockMvc.perform(get(BASE_URL)
          .header("X-Authorities", VIEW_AUTHORITY)
          .header("X-User", TEST_USER))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].eventId").value(EVENT_ID.toString()))
          .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("should return 200 when filter params status and eventType are provided")
    void listAccountingEvents_returns200_withFilterParams() throws Exception {
      when(eventIngestionService.listEvents(any(), any(Pageable.class))).thenReturn(Page.empty());

      mockMvc.perform(get(BASE_URL)
          .param("status", "PROCESSED")
          .param("eventType", "INVOICE")
          .header("X-Authorities", VIEW_AUTHORITY)
          .header("X-User", TEST_USER))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("should return 403 when caller lacks accounting:events:view authority")
    void listAccountingEvents_returns403_whenUnauthorized() throws Exception {
      mockMvc.perform(get(BASE_URL)
          .header("X-Authorities", "accounting:read")
          .header("X-User", TEST_USER))
          .andExpect(status().isForbidden());
    }
  }

  @Nested
  @DisplayName("GET /v1/accounting/events/{eventId}/processing-log")
  class GetEventProcessingLog {

    @Test
    @DisplayName("should return 200 with JSON array when caller has accounting:events:view")
    void getEventProcessingLog_returns200_asList() throws Exception {
      List<EventProcessingLogEntry> log = List.of(
          EventProcessingLogEntry.builder()
              .entryId(UUID.randomUUID())
              .occurredAt(Instant.parse("2025-06-01T10:00:00Z"))
              .severity("INFO")
              .message("Event received and queued for processing")
              .build(),
          EventProcessingLogEntry.builder()
              .entryId(UUID.randomUUID())
              .occurredAt(Instant.parse("2025-06-01T10:01:00Z"))
              .severity("INFO")
              .message("Journal entry created successfully")
              .build());
      when(eventIngestionService.getEventProcessingLog(EVENT_ID)).thenReturn(log);

      mockMvc.perform(get(BASE_URL + "/{eventId}/processing-log", EVENT_ID)
          .header("X-Authorities", VIEW_AUTHORITY)
          .header("X-User", TEST_USER))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$").isArray())
          .andExpect(jsonPath("$.length()").value(2))
          .andExpect(jsonPath("$[0].severity").value("INFO"))
          .andExpect(jsonPath("$[1].message").value("Journal entry created successfully"));
    }

    @Test
    @DisplayName("should return 403 when caller lacks accounting:events:view authority")
    void getEventProcessingLog_returns403_whenUnauthorized() throws Exception {
      mockMvc.perform(get(BASE_URL + "/{eventId}/processing-log", EVENT_ID)
          .header("X-Authorities", "accounting:read")
          .header("X-User", TEST_USER))
          .andExpect(status().isForbidden());
    }
  }
}
