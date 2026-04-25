package com.positivity.accounting.internal.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.positivity.accounting.internal.dto.ReportExportRequest;
import com.positivity.accounting.internal.dto.ReportExportResponse;
import com.positivity.accounting.internal.enums.ExportFormat;
import com.positivity.accounting.internal.enums.ExportStatus;
import com.positivity.accounting.service.ReportExportService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for ReportExportController.
 *
 * <p>
 * Tests the three async report export endpoints:
 * <ul>
 * <li>POST /v1/accounting/reports/export — submit export job (201)</li>
 * <li>GET /v1/accounting/reports/export/{exportId} — poll status (200/404)</li>
 * <li>GET /v1/accounting/reports/export — list history (200)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("ReportExportController Tests")
class ReportExportControllerTest {

  private static final String BASE_URL = "/v1/accounting/reports/export";
  private static final String TEST_AUTHORITIES = "accounting:report:export";
  private static final String TEST_USER = "test-user";

  private static final UUID EXPORT_ID = UUID.fromString("a10217f9-3ec6-46b9-9c87-e7066c100c24");
  private static final UUID ORG_ID = UUID.fromString("b10217f9-3ec6-46b9-9c87-e7066c100c24");
  private static final Instant REQUESTED_AT = Instant.parse("2025-01-01T10:00:00Z");

  @Autowired
  private WebApplicationContext context;

  @Autowired
  private ObjectMapper objectMapper;

  @MockitoBean
  private ReportExportService reportExportService;

  private MockMvc mockMvc;

  private ReportExportRequest validRequest;
  private ReportExportResponse exportResponse;

  @BeforeEach
  void setUp() {
    mockMvc = webAppContextSetup(context).apply(springSecurity()).build();

    validRequest = ReportExportRequest.builder()
        .format(ExportFormat.CSV)
        .reportType("JOURNAL_LINES")
        .startDate(LocalDate.of(2025, 1, 1))
        .endDate(LocalDate.of(2025, 3, 31))
        .organizationId(ORG_ID)
        .build();

    exportResponse = ReportExportResponse.builder()
        .exportId(EXPORT_ID)
        .status(ExportStatus.PENDING)
        .requestedAt(REQUESTED_AT)
        .format(ExportFormat.CSV)
        .reportType("JOURNAL_LINES")
        .build();
  }

  @Nested
  @DisplayName("POST /v1/accounting/reports/export")
  class RequestExport {

    @Test
    @DisplayName("should return 201 with export job when request is valid")
    void requestExport_whenValid_returns201() throws Exception {
      when(reportExportService.requestExport(
          any(ReportExportRequest.class), any(String.class)))
          .thenReturn(exportResponse);

      mockMvc.perform(post(BASE_URL)
          .header("X-Authorities", TEST_AUTHORITIES)
          .header("X-User", TEST_USER)
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsString(validRequest)))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.exportId").value(EXPORT_ID.toString()))
          .andExpect(jsonPath("$.status").value("PENDING"))
          .andExpect(jsonPath("$.format").value("CSV"))
          .andExpect(jsonPath("$.reportType").value("JOURNAL_LINES"));
    }

    @Test
    @DisplayName("should return 400 when required fields are missing")
    void requestExport_whenInvalid_returns400() throws Exception {
      mockMvc.perform(post(BASE_URL)
          .header("X-Authorities", TEST_AUTHORITIES)
          .header("X-User", TEST_USER)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should return 400 when endDate is before startDate")
    void requestExport_whenEndDateBeforeStartDate_returns400() throws Exception {
      ReportExportRequest invalidRange = ReportExportRequest.builder()
          .format(ExportFormat.CSV)
          .reportType("JOURNAL_LINES")
          .startDate(LocalDate.of(2025, 3, 31))
          .endDate(LocalDate.of(2025, 1, 1))
          .organizationId(ORG_ID)
          .build();

      mockMvc.perform(post(BASE_URL)
          .header("X-Authorities", TEST_AUTHORITIES)
          .header("X-User", TEST_USER)
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsString(invalidRange)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.message", containsString("endDate")));
    }

    @Test
    @DisplayName("should return 403 when caller lacks accounting:report:export authority")
    void requestExport_whenUnauthorized_returns403() throws Exception {
      mockMvc.perform(post(BASE_URL)
          .header("X-Authorities", "accounting:read")
          .header("X-User", TEST_USER)
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsString(validRequest)))
          .andExpect(status().isForbidden());
    }
  }

  @Nested
  @DisplayName("GET /v1/accounting/reports/export/{exportId}")
  class GetExportStatus {

    @Test
    @DisplayName("should return 200 with export status when found")
    void getExportStatus_whenFound_returns200() throws Exception {
      when(reportExportService.getExportStatus(EXPORT_ID))
          .thenReturn(exportResponse);

      mockMvc.perform(get(BASE_URL + "/{exportId}", EXPORT_ID)
          .header("X-Authorities", TEST_AUTHORITIES)
          .header("X-User", TEST_USER))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.exportId").value(EXPORT_ID.toString()))
          .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("should return 404 when export job is not found")
    void getExportStatus_whenNotFound_returns404() throws Exception {
      when(reportExportService.getExportStatus(EXPORT_ID))
          .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Export not found: " + EXPORT_ID));

      mockMvc.perform(get(BASE_URL + "/{exportId}", EXPORT_ID)
          .header("X-Authorities", TEST_AUTHORITIES)
          .header("X-User", TEST_USER))
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("GET /v1/accounting/reports/export")
  class GetExportHistory {

    @Test
    @DisplayName("should return 200 with paginated export history")
    void getExportHistory_returns200() throws Exception {
      Page<ReportExportResponse> page = new PageImpl<>(List.of(exportResponse), PageRequest.of(0, 20), 1);
      when(reportExportService.getExportHistory(any(Pageable.class)))
          .thenReturn(page);

      mockMvc.perform(get(BASE_URL)
          .header("X-Authorities", TEST_AUTHORITIES)
          .header("X-User", TEST_USER))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].exportId").value(EXPORT_ID.toString()))
          .andExpect(jsonPath("$.content[0].status").value("PENDING"))
          .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("should return 403 when caller lacks accounting:report:export authority")
    void getExportHistory_whenUnauthorized_returns403() throws Exception {
      mockMvc.perform(get(BASE_URL)
          .header("X-Authorities", "accounting:read")
          .header("X-User", TEST_USER))
          .andExpect(status().isForbidden());
    }
  }
}
