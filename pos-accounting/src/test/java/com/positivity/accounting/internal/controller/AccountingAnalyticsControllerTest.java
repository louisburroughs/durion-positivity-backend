package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.CollectionsAnalyticsReport;
import com.positivity.accounting.internal.dto.PaymentLagCohortRow;
import com.positivity.accounting.internal.dto.PaymentLagCohortsReport;
import com.positivity.accounting.internal.service.AccountingAnalyticsService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Controller tests for {@link AccountingAnalyticsController}: happy path, input validation (400),
 * and authorization (403), for both the collections (E2) and payment-lag-cohorts (E3) endpoints.
 */
@DisplayName("AccountingAnalyticsController Tests")
class AccountingAnalyticsControllerTest extends BaseIntegrationTest {

    private static final String COLLECTIONS_PATH = "/v1/accounting/analytics/collections";
    private static final String COHORTS_PATH = "/v1/accounting/analytics/payment-lag-cohorts";

    @MockitoBean
    private AccountingAnalyticsService accountingAnalyticsService;

    private CollectionsAnalyticsReport stubCollectionsReport() {
        return CollectionsAnalyticsReport.builder()
                .startDate(LocalDate.of(2026, 6, 1))
                .endDate(LocalDate.of(2026, 6, 30))
                .generatedAt(Instant.parse("2026-06-30T08:00:00Z"))
                .invoiced(new BigDecimal("1500.00"))
                .collected(new BigDecimal("1200.00"))
                .collectionRatePct(new BigDecimal("80.00"))
                .build();
    }

    private PaymentLagCohortsReport stubCohortsReport() {
        return PaymentLagCohortsReport.builder()
                .issuedFrom(LocalDate.of(2026, 1, 1))
                .issuedTo(LocalDate.of(2026, 6, 30))
                .generatedAt(Instant.parse("2026-06-30T08:00:00Z"))
                .cohorts(List.of(
                        PaymentLagCohortRow.builder()
                                .cohort("<=30")
                                .invoiceCount(1)
                                .amount(new BigDecimal("100.00"))
                                .build(),
                        PaymentLagCohortRow.builder()
                                .cohort("unpaid")
                                .invoiceCount(2)
                                .amount(new BigDecimal("900.00"))
                                .build()))
                .build();
    }

    @Test
    @DisplayName("GET /collections returns 200 with the report for an authorized request")
    void collectionsReturns200() throws Exception {
        when(accountingAnalyticsService.getCollectionsAnalytics(
                        eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 30))))
                .thenReturn(stubCollectionsReport());

        mockMvc.perform(withAuth(
                        get(COLLECTIONS_PATH).param("startDate", "2026-06-01").param("endDate", "2026-06-30")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiced").value(1500.00))
                .andExpect(jsonPath("$.collected").value(1200.00))
                .andExpect(jsonPath("$.collectionRatePct").value(80.00));
    }

    @Test
    @DisplayName("GET /collections rejects endDate before startDate with 400 VALIDATION_ERROR")
    void collectionsRejectsInvalidRange() throws Exception {
        mockMvc.perform(withAuth(
                        get(COLLECTIONS_PATH).param("startDate", "2026-06-30").param("endDate", "2026-06-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /collections returns 403 when the caller lacks accounting:analytics:view")
    void collectionsReturns403WithoutPermission() throws Exception {
        mockMvc.perform(withAuth(
                        get(COLLECTIONS_PATH).param("startDate", "2026-06-01").param("endDate", "2026-06-30"),
                        "accounting:je:view"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /payment-lag-cohorts returns 200 with the report for an authorized request")
    void cohortsReturns200() throws Exception {
        when(accountingAnalyticsService.getPaymentLagCohorts(
                        eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 6, 30)), anyInt()))
                .thenReturn(stubCohortsReport());

        mockMvc.perform(withAuth(
                        get(COHORTS_PATH).param("issuedFrom", "2026-01-01").param("issuedTo", "2026-06-30")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts.length()").value(2))
                .andExpect(jsonPath("$.cohorts[0].cohort").value("<=30"))
                .andExpect(jsonPath("$.cohorts[1].cohort").value("unpaid"));
    }

    @Test
    @DisplayName("GET /payment-lag-cohorts passes an explicit limit through to the service")
    void cohortsPassesLimit() throws Exception {
        when(accountingAnalyticsService.getPaymentLagCohorts(any(), any(), eq(2)))
                .thenReturn(stubCohortsReport());

        mockMvc.perform(withAuth(get(COHORTS_PATH)
                        .param("issuedFrom", "2026-01-01")
                        .param("issuedTo", "2026-06-30")
                        .param("limit", "2")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /payment-lag-cohorts rejects issuedTo before issuedFrom with 400 VALIDATION_ERROR")
    void cohortsRejectsInvalidRange() throws Exception {
        mockMvc.perform(withAuth(
                        get(COHORTS_PATH).param("issuedFrom", "2026-06-30").param("issuedTo", "2026-01-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /payment-lag-cohorts returns 403 when the caller lacks accounting:analytics:view")
    void cohortsReturns403WithoutPermission() throws Exception {
        mockMvc.perform(withAuth(
                        get(COHORTS_PATH).param("issuedFrom", "2026-01-01").param("issuedTo", "2026-06-30"),
                        "accounting:je:view"))
                .andExpect(status().isForbidden());
    }
}
