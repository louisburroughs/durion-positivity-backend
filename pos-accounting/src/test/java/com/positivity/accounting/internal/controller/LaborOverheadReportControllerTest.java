package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.LaborOverheadCostReport;
import com.positivity.accounting.service.LaborOverheadReportService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Controller tests for {@link LaborOverheadReportController}: happy path, input validation (400),
 * and authorization (403).
 */
@DisplayName("LaborOverheadReportController Tests")
class LaborOverheadReportControllerTest extends BaseIntegrationTest {

    private static final String PATH = "/v1/accounting/reports/location/labor-overhead";

    @MockitoBean
    private LaborOverheadReportService laborOverheadReportService;

    private LaborOverheadCostReport stubReport() {
        return LaborOverheadCostReport.builder()
                .locationId("LOC-107")
                .locationLabel("LOC-107")
                .fiscalYear(2026)
                .asOfMonth(12)
                .currency("USD")
                .localCurrencyPerUsd(new BigDecimal("1.00"))
                .averageRate(new BigDecimal("1.00"))
                .lines(List.of())
                .build();
    }

    @Test
    @DisplayName("Returns 200 with the report for an authorized request")
    void returns200() throws Exception {
        when(laborOverheadReportService.generate(eq("LOC-107"), eq(2026), any()))
                .thenReturn(stubReport());

        mockMvc.perform(withAuth(get(PATH).param("locationId", "LOC-107").param("fiscalYear", "2026")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value("LOC-107"))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    @DisplayName("Rejects asOfMonth outside 1-12 with 400 VALIDATION_ERROR")
    void rejectsInvalidAsOfMonth() throws Exception {
        mockMvc.perform(withAuth(get(PATH)
                        .param("locationId", "LOC-107")
                        .param("fiscalYear", "2026")
                        .param("asOfMonth", "13")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("Rejects a non-four-digit fiscalYear with 400 VALIDATION_ERROR")
    void rejectsInvalidFiscalYear() throws Exception {
        mockMvc.perform(withAuth(get(PATH).param("locationId", "LOC-107").param("fiscalYear", "12")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("Returns 403 when the caller lacks the financial-reporting permission")
    void returns403WithoutPermission() throws Exception {
        mockMvc.perform(withAuth(
                        get(PATH).param("locationId", "LOC-107").param("fiscalYear", "2026"), "accounting:je:view"))
                .andExpect(status().isForbidden());
    }
}
