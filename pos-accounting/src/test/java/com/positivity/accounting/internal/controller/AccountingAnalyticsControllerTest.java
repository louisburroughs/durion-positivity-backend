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
import com.positivity.accounting.internal.dto.VendorSpendReport;
import com.positivity.accounting.internal.dto.VendorSpendRow;
import com.positivity.accounting.internal.service.AccountingAnalyticsService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Controller tests for {@link AccountingAnalyticsController}: happy path, input validation (400),
 * and authorization (403), for the collections (E2), payment-lag-cohorts (E3), and vendor-spend
 * (E8) endpoints.
 */
@DisplayName("AccountingAnalyticsController Tests")
class AccountingAnalyticsControllerTest extends BaseIntegrationTest {

    private static final String COLLECTIONS_PATH = "/v1/accounting/analytics/collections";
    private static final String COHORTS_PATH = "/v1/accounting/analytics/payment-lag-cohorts";
    private static final String VENDOR_SPEND_PATH = "/v1/accounting/analytics/vendor-spend";

    @MockitoBean
    private AccountingAnalyticsService accountingAnalyticsService;

    private CollectionsAnalyticsReport stubCollectionsReport() {
        return CollectionsAnalyticsReport.builder()
                .startDate(LocalDate.of(2026, 6, 1))
                .endDate(LocalDate.of(2026, 6, 30))
                .generatedAt(Instant.parse("2026-06-30T08:00:00Z"))
                .invoiced(new BigDecimal("1500.00"))
                .collected(new BigDecimal("1200.00"))
                .applicationReversals(new BigDecimal("150.00"))
                .collectionRatePct(new BigDecimal("80.00"))
                .refunded(new BigDecimal("50.00"))
                .netCashCollected(new BigDecimal("1150.00"))
                .received(new BigDecimal("1300.00"))
                .nonCashSettled(new BigDecimal("100.00"))
                .settled(new BigDecimal("1300.00"))
                .settlementRatePct(new BigDecimal("86.67"))
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
                .andExpect(jsonPath("$.applicationReversals").value(150.00))
                .andExpect(jsonPath("$.collectionRatePct").value(80.00))
                .andExpect(jsonPath("$.refunded").value(50.00))
                .andExpect(jsonPath("$.netCashCollected").value(1150.00))
                .andExpect(jsonPath("$.received").value(1300.00))
                .andExpect(jsonPath("$.nonCashSettled").value(100.00))
                .andExpect(jsonPath("$.settled").value(1300.00))
                .andExpect(jsonPath("$.settlementRatePct").value(86.67));
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
    @DisplayName("GET /collections serializes a negative collected unclamped alongside a gross applicationReversals")
    void collectionsSerializesNegativeCollectedUnclamped() throws Exception {
        when(accountingAnalyticsService.getCollectionsAnalytics(
                        eq(LocalDate.of(2026, 3, 1)), eq(LocalDate.of(2026, 3, 31))))
                .thenReturn(CollectionsAnalyticsReport.builder()
                        .startDate(LocalDate.of(2026, 3, 1))
                        .endDate(LocalDate.of(2026, 3, 31))
                        .generatedAt(Instant.parse("2026-03-31T08:00:00Z"))
                        .invoiced(new BigDecimal("1000.00"))
                        .collected(new BigDecimal("-800.00"))
                        .applicationReversals(new BigDecimal("900.00"))
                        .collectionRatePct(new BigDecimal("-80.00"))
                        .refunded(new BigDecimal("300.00"))
                        .netCashCollected(new BigDecimal("-1100.00"))
                        .received(new BigDecimal("0.00"))
                        .nonCashSettled(new BigDecimal("0.00"))
                        .settled(new BigDecimal("-800.00"))
                        .settlementRatePct(new BigDecimal("-80.00"))
                        .build());

        mockMvc.perform(withAuth(
                        get(COLLECTIONS_PATH).param("startDate", "2026-03-01").param("endDate", "2026-03-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collected").value(-800.00))
                .andExpect(jsonPath("$.applicationReversals").value(900.00))
                .andExpect(jsonPath("$.collectionRatePct").value(-80.00))
                .andExpect(jsonPath("$.netCashCollected").value(-1100.00))
                .andExpect(jsonPath("$.settled").value(-800.00))
                .andExpect(jsonPath("$.settlementRatePct").value(-80.00));
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
        PaymentLagCohortsReport truncatedReport = PaymentLagCohortsReport.builder()
                .issuedFrom(LocalDate.of(2026, 1, 1))
                .issuedTo(LocalDate.of(2026, 6, 30))
                .generatedAt(Instant.parse("2026-06-30T08:00:00Z"))
                .truncated(true)
                .cohorts(List.of(PaymentLagCohortRow.builder()
                        .cohort("<=30")
                        .invoiceCount(1)
                        .amount(new BigDecimal("100.00"))
                        .build()))
                .build();
        when(accountingAnalyticsService.getPaymentLagCohorts(any(), any(), eq(2)))
                .thenReturn(truncatedReport);

        mockMvc.perform(withAuth(get(COHORTS_PATH)
                        .param("issuedFrom", "2026-01-01")
                        .param("issuedTo", "2026-06-30")
                        .param("limit", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(true));
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

    private VendorSpendReport stubVendorSpendReport() {
        return VendorSpendReport.builder()
                .startDate(LocalDate.of(2026, 6, 1))
                .endDate(LocalDate.of(2026, 6, 30))
                .generatedAt(Instant.parse("2026-06-30T08:00:00Z"))
                .limit(20)
                .truncated(false)
                .rows(List.of(VendorSpendRow.builder()
                        .vendorId(UUID.fromString("018f0000-0000-7000-8000-0000000000aa"))
                        .name("Acme Parts Co")
                        .paidAmount(new BigDecimal("1000.00"))
                        .billsIssuedInWindow(2)
                        .avgIssuedBillAmount(new BigDecimal("250.00"))
                        .build()))
                .build();
    }

    @Test
    @DisplayName("GET /vendor-spend returns 200 with the report for an authorized request")
    void vendorSpendReturns200() throws Exception {
        when(accountingAnalyticsService.getVendorSpend(
                        eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 30)), anyInt()))
                .thenReturn(stubVendorSpendReport());

        mockMvc.perform(withAuth(
                        get(VENDOR_SPEND_PATH).param("startDate", "2026-06-01").param("endDate", "2026-06-30")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].paidAmount").value(1000.00))
                .andExpect(jsonPath("$.rows[0].billsIssuedInWindow").value(2))
                .andExpect(jsonPath("$.rows[0].avgIssuedBillAmount").value(250.00))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    @DisplayName("GET /vendor-spend passes an explicit limit through to the service")
    void vendorSpendPassesLimit() throws Exception {
        when(accountingAnalyticsService.getVendorSpend(any(), any(), eq(5))).thenReturn(stubVendorSpendReport());

        mockMvc.perform(withAuth(get(VENDOR_SPEND_PATH)
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-30")
                        .param("limit", "5")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /vendor-spend rejects endDate before startDate with 400 VALIDATION_ERROR")
    void vendorSpendRejectsInvalidRange() throws Exception {
        mockMvc.perform(withAuth(
                        get(VENDOR_SPEND_PATH).param("startDate", "2026-06-30").param("endDate", "2026-06-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /vendor-spend rejects a non-positive limit with 400 VALIDATION_ERROR")
    void vendorSpendRejectsNonPositiveLimit() throws Exception {
        when(accountingAnalyticsService.getVendorSpend(any(), any(), eq(0)))
                .thenThrow(new IllegalArgumentException("limit must be at least 1"));

        mockMvc.perform(withAuth(get(VENDOR_SPEND_PATH)
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-30")
                        .param("limit", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /vendor-spend returns 403 when the caller lacks accounting:analytics:view")
    void vendorSpendReturns403WithoutPermission() throws Exception {
        mockMvc.perform(withAuth(
                        get(VENDOR_SPEND_PATH).param("startDate", "2026-06-01").param("endDate", "2026-06-30"),
                        "accounting:je:view"))
                .andExpect(status().isForbidden());
    }
}
