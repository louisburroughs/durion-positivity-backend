package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.ExportJobRequest;
import com.positivity.accounting.internal.dto.ExportJobResponse;
import com.positivity.accounting.service.TimekeepingExportService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TimekeepingExportController Tests")
class TimekeepingExportControllerTest extends BaseIntegrationTest {

  private static final String BASE_URL = "/v1/accounting/export";
  private static final String EXPORT_AUTHORITY = "accounting:export:request";
  private static final String VIEW_AUTHORITY = "accounting:export:view";
  private static final String TEST_USER = "test-user";

  private static final UUID JOB_ID = UUID.fromString("c1234567-89ab-cdef-0123-456789abcdef");
  private static final Instant REQUESTED_AT = Instant.parse("2025-06-01T09:00:00Z");

  @MockitoBean
  private TimekeepingExportService timekeepingExportService;

  private ExportJobRequest validRequest;
  private ExportJobResponse jobResponse;

  @BeforeEach
  void setUp() {
    validRequest = ExportJobRequest.builder()
        .exportType("TIMEKEEPING")
        .format("CSV")
        .build();

    jobResponse = ExportJobResponse.builder()
        .jobId(JOB_ID)
        .status("PENDING")
        .requestedAt(REQUESTED_AT)
        .build();
  }

  @Nested
  @DisplayName("POST /v1/accounting/export")
  class RequestExport {

    @Test
    @DisplayName("should return 202 when request is valid and caller has accounting:export:request")
    void requestExport_returns202_whenValid() throws Exception {
      when(timekeepingExportService.requestExport(any(ExportJobRequest.class)))
          .thenReturn(jobResponse);

      mockMvc.perform(post(BASE_URL)
          .header("X-Authorities", EXPORT_AUTHORITY)
          .header("X-User", TEST_USER)
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsString(validRequest)))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
          .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("should return 403 when caller lacks accounting:export:request authority")
    void requestExport_returns403_whenUnauthorized() throws Exception {
      mockMvc.perform(post(BASE_URL)
          .header("X-Authorities", "accounting:read")
          .header("X-User", TEST_USER)
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsString(validRequest)))
          .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("should return 400 when required fields are missing")
    void requestExport_returns400_whenInvalidBody() throws Exception {
      mockMvc.perform(post(BASE_URL)
          .header("X-Authorities", EXPORT_AUTHORITY)
          .header("X-User", TEST_USER)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{}"))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("GET /v1/accounting/export/status/{jobId}")
  class GetExportStatus {

    @Test
    @DisplayName("should return 200 with job status when found and caller has accounting:export:view")
    void getExportStatus_returns200_whenFound() throws Exception {
      when(timekeepingExportService.getExportStatus(JOB_ID))
          .thenReturn(jobResponse);

      mockMvc.perform(get(BASE_URL + "/status/{jobId}", JOB_ID)
          .header("X-Authorities", VIEW_AUTHORITY)
          .header("X-User", TEST_USER))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
          .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("should return 404 when job is not found")
    void getExportStatus_returns404_whenNotFound() throws Exception {
      UUID unknownId = UUID.randomUUID();
      when(timekeepingExportService.getExportStatus(unknownId))
          .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Export job not found: " + unknownId));

      mockMvc.perform(get(BASE_URL + "/status/{jobId}", unknownId)
          .header("X-Authorities", VIEW_AUTHORITY)
          .header("X-User", TEST_USER))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("should return 403 when caller lacks accounting:export:view authority")
    void getExportStatus_returns403_whenUnauthorized() throws Exception {
      mockMvc.perform(get(BASE_URL + "/status/{jobId}", JOB_ID)
          .header("X-Authorities", "accounting:read")
          .header("X-User", TEST_USER))
          .andExpect(status().isForbidden());
    }
  }

  @Nested
  @DisplayName("GET /v1/accounting/export/history")
  class ListExportHistory {

    @Test
    @DisplayName("should return 200 with paginated history when caller has accounting:export:view")
    void listExportHistory_returns200_withPage() throws Exception {
      Page<ExportJobResponse> page = new PageImpl<>(
          List.of(jobResponse), PageRequest.of(0, 20), 1);
      when(timekeepingExportService.listExportHistory(any(Pageable.class)))
          .thenReturn(page);

      mockMvc.perform(get(BASE_URL + "/history")
          .header("X-Authorities", VIEW_AUTHORITY)
          .header("X-User", TEST_USER))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].jobId").value(JOB_ID.toString()))
          .andExpect(jsonPath("$.content[0].status").value("PENDING"))
          .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("should return 403 when caller lacks accounting:export:view authority")
    void listExportHistory_returns403_whenUnauthorized() throws Exception {
      mockMvc.perform(get(BASE_URL + "/history")
          .header("X-Authorities", "accounting:read")
          .header("X-User", TEST_USER))
          .andExpect(status().isForbidden());
    }
  }
}
