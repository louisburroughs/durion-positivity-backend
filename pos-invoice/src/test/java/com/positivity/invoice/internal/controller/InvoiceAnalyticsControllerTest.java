package com.positivity.invoice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.invoice.internal.dto.InvoicingLagReport;
import com.positivity.invoice.internal.dto.InvoicingLagRow;
import com.positivity.invoice.internal.dto.RevenueByCustomerReport;
import com.positivity.invoice.internal.dto.RevenueByCustomerRow;
import com.positivity.invoice.internal.service.InvoiceAnalyticsService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller-layer unit tests for {@link InvoiceAnalyticsController}. Standalone MockMvc (no
 * Spring context, so no method security runs — the {@code invoice:analytics:view} guard is
 * verified by reflection-based tests elsewhere in this module's convention); focuses on param
 * binding, the limit default/clamp/reject contract, and 400 mapping for a bad date range.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceAnalyticsControllerTest {

    @Mock
    private InvoiceAnalyticsService invoiceAnalyticsService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InvoiceAnalyticsController controller = new InvoiceAnalyticsController(invoiceAnalyticsService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new InvoiceAnalyticsExceptionHandler(
                        Clock.fixed(Instant.parse("2026-08-11T09:00:00Z"), ZoneOffset.UTC)))
                .build();
    }

    private RevenueByCustomerReport revenueReport() {
        return RevenueByCustomerReport.builder()
                .startDate(LocalDate.parse("2026-06-01"))
                .endDate(LocalDate.parse("2026-06-30"))
                .limit(20)
                .truncated(false)
                .rows(List.of(RevenueByCustomerRow.builder()
                        .customerId("018f0000-0000-7000-8000-0000000000aa")
                        .name("Acme Towing LLC")
                        .revenue(new BigDecimal("300.0000"))
                        .invoiceCount(3)
                        .avgInvoiceValue(new BigDecimal("100.0000"))
                        .lastInvoiceDate(Instant.parse("2026-06-20T00:00:00Z"))
                        .build()))
                .build();
    }

    @Test
    void getRevenueByCustomer_defaultsLimitTo20WhenOmitted() throws Exception {
        when(invoiceAnalyticsService.revenueByCustomer(any(), any(), eq(20))).thenReturn(revenueReport());

        mockMvc.perform(get("/v1/invoices/analytics/revenue-by-customer")
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].customerId").value("018f0000-0000-7000-8000-0000000000aa"))
                .andExpect(jsonPath("$.rows[0].name").value("Acme Towing LLC"))
                .andExpect(jsonPath("$.rows[0].avgInvoiceValue").value(100.0))
                .andExpect(jsonPath("$.truncated").value(false));

        verify(invoiceAnalyticsService).revenueByCustomer(any(), any(), eq(20));
    }

    @Test
    void getRevenueByCustomer_delegatesExplicitLimit() throws Exception {
        when(invoiceAnalyticsService.revenueByCustomer(any(), any(), eq(5))).thenReturn(revenueReport());

        mockMvc.perform(get("/v1/invoices/analytics/revenue-by-customer")
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-30")
                        .param("limit", "5"))
                .andExpect(status().isOk());

        verify(invoiceAnalyticsService).revenueByCustomer(any(), any(), eq(5));
    }

    @Test
    void getRevenueByCustomer_clampsAnOverLargeLimitToTheHardCap() throws Exception {
        when(invoiceAnalyticsService.revenueByCustomer(any(), any(), eq(100))).thenReturn(revenueReport());

        mockMvc.perform(get("/v1/invoices/analytics/revenue-by-customer")
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-30")
                        .param("limit", "500"))
                .andExpect(status().isOk());

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(invoiceAnalyticsService).revenueByCustomer(any(), any(), limitCaptor.capture());
        assertThat(limitCaptor.getValue()).isEqualTo(100);
    }

    @Test
    void getRevenueByCustomer_rejectsNonPositiveLimit() throws Exception {
        mockMvc.perform(get("/v1/invoices/analytics/revenue-by-customer")
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-30")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(invoiceAnalyticsService, never()).revenueByCustomer(any(), any(), anyInt());
    }

    @Test
    void getRevenueByCustomer_endDateBeforeStartDate_returns400() throws Exception {
        mockMvc.perform(get("/v1/invoices/analytics/revenue-by-customer")
                        .param("startDate", "2026-06-30")
                        .param("endDate", "2026-06-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getInvoicingLag_delegatesDatesAndSerializesReport() throws Exception {
        InvoicingLagReport report = InvoicingLagReport.builder()
                .startDate(LocalDate.parse("2026-06-01"))
                .endDate(LocalDate.parse("2026-06-30"))
                .rows(List.of(InvoicingLagRow.builder()
                        .avgDaysWoCreationToInvoice(3.5)
                        .count(10)
                        .build()))
                .build();
        when(invoiceAnalyticsService.invoicingLag(any(), any())).thenReturn(report);

        mockMvc.perform(get("/v1/invoices/analytics/invoicing-lag")
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].avgDaysWoCreationToInvoice").value(3.5))
                .andExpect(jsonPath("$.rows[0].count").value(10));
    }

    @Test
    void getInvoicingLag_nullAverageSerializesAsJsonNull_notZero() throws Exception {
        InvoicingLagReport report = InvoicingLagReport.builder()
                .startDate(LocalDate.parse("2026-06-01"))
                .endDate(LocalDate.parse("2026-06-30"))
                .rows(List.of(InvoicingLagRow.builder()
                        .avgDaysWoCreationToInvoice(null)
                        .count(0)
                        .build()))
                .build();
        when(invoiceAnalyticsService.invoicingLag(any(), any())).thenReturn(report);

        mockMvc.perform(get("/v1/invoices/analytics/invoicing-lag")
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].avgDaysWoCreationToInvoice").doesNotExist())
                .andExpect(jsonPath("$.rows[0].count").value(0));
    }

    @Test
    void getInvoicingLag_endDateBeforeStartDate_returns400() throws Exception {
        mockMvc.perform(get("/v1/invoices/analytics/invoicing-lag")
                        .param("startDate", "2026-06-30")
                        .param("endDate", "2026-06-01"))
                .andExpect(status().isBadRequest());
    }
}
