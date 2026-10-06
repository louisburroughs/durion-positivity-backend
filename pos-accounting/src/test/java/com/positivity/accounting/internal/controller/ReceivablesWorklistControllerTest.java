package com.positivity.accounting.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseControllerSliceTest;
import com.positivity.accounting.internal.dto.CustomerOpenInvoicesPage;
import com.positivity.accounting.internal.dto.OpenInvoiceRow;
import com.positivity.accounting.internal.dto.OpenInvoicesSummary;
import com.positivity.accounting.internal.dto.PaymentMatchSuggestion;
import com.positivity.accounting.internal.dto.SuggestedInvoice;
import com.positivity.accounting.internal.dto.UnappliedPaymentRow;
import com.positivity.accounting.internal.dto.UnappliedPaymentsPage;
import com.positivity.accounting.internal.dto.UnappliedPaymentsSummary;
import com.positivity.accounting.internal.service.ReceivablesWorklistService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The two receivables worklist reads of #2502 through the production security chain and error
 * advice: 200, 400 {@code VALIDATION_ERROR} and 403 (AC8, AC9), and their OpenAPI and event
 * annotations.
 */
@DisplayName("Receivables worklist controllers (#2502)")
@WebMvcTest({ReceivablePaymentController.class, CustomerReceivablesController.class})
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class ReceivablesWorklistControllerTest extends BaseControllerSliceTest {

    private static final String APPLY = "accounting:payment:apply";
    private static final UUID CUSTOMER_ID = UUID.fromString("0198a000-0000-7000-8000-000000000412");
    private static final UUID PAYMENT_ID = UUID.fromString("0199a000-0000-7000-8000-000000000101");
    private static final UUID INVOICE_ID = UUID.fromString("0199a000-0000-7000-8000-000000001702");
    private static final String PAYMENTS = "/v1/accounting/receivable-payments";
    private static final String OPEN_INVOICES = "/v1/accounting/customers/" + CUSTOMER_ID + "/open-invoices";

    @MockitoBean
    private ReceivablesWorklistService service;

    @Nested
    @DisplayName("GET /receivable-payments")
    class Payments {

        @Test
        @DisplayName("200 with rows, suggestion and summary for a holder of accounting:payment:apply")
        void lists() throws Exception {
            when(service.listUnappliedPayments(isNull(), eq(0), eq(25))).thenReturn(page());

            mockMvc.perform(withAuth(get(PAYMENTS), APPLY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].paymentId").value(PAYMENT_ID.toString()))
                    .andExpect(jsonPath("$.items[0].customerDisplayName").value("Rivera Trucking"))
                    .andExpect(jsonPath("$.items[0].customerReference").value("CUST-00412"))
                    .andExpect(jsonPath("$.items[0].paymentMethod").value("CARD"))
                    .andExpect(jsonPath("$.items[0].receivedAt").value("2026-10-04T15:12:09Z"))
                    .andExpect(jsonPath("$.items[0].sourceInvoiceNumber").value("INV-2026-01702"))
                    .andExpect(jsonPath("$.items[0].suggestion.reasons[0]").value("REMITTANCE_REFERENCE"))
                    .andExpect(jsonPath("$.items[0].suggestion.invoices[0].suggestedAmount")
                            .value(4615.00))
                    .andExpect(jsonPath("$.items[0].suggestion.leftOver").value(0.00))
                    .andExpect(jsonPath("$.summary.count").value(1))
                    .andExpect(jsonPath("$.summary.totalUnappliedAmount").value(4615.00))
                    .andExpect(jsonPath("$.summary.currency").value("USD"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("passes the customer filter and paging through")
        void passesFilter() throws Exception {
            when(service.listUnappliedPayments(eq(CUSTOMER_ID), eq(2), eq(100))).thenReturn(page());

            mockMvc.perform(withAuth(
                            get(PAYMENTS)
                                    .param("status", "AVAILABLE")
                                    .param("customerId", CUSTOMER_ID.toString())
                                    .param("page", "2")
                                    .param("size", "100"),
                            APPLY))
                    .andExpect(status().isOk());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"status=FULLY_APPLIED", "size=101", "size=0", "page=-1", "customerId=not-a-uuid"})
        @DisplayName("400 VALIDATION_ERROR (AC9)")
        void rejects(String query) throws Exception {
            mockMvc.perform(withAuth(get(PAYMENTS + "?" + query), APPLY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            verify(service, never()).listUnappliedPayments(any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("403 ApiError without accounting:payment:apply (AC8)")
        void forbidden() throws Exception {
            mockMvc.perform(withAuth(get(PAYMENTS), "accounting:payment:reverse"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").exists());
            verify(service, never()).listUnappliedPayments(any(), anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("GET /customers/{customerId}/open-invoices")
    class OpenInvoices {

        @Test
        @DisplayName("200 with rows and summary, default page size 100")
        void lists() throws Exception {
            when(service.listOpenInvoices(CUSTOMER_ID, 0, 100)).thenReturn(openInvoices());

            mockMvc.perform(withAuth(get(OPEN_INVOICES), APPLY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].invoiceId").value(INVOICE_ID.toString()))
                    .andExpect(jsonPath("$.items[0].balanceDue").value(360.00))
                    .andExpect(jsonPath("$.items[0].arStatus").value("PARTIALLY_PAID"))
                    .andExpect(jsonPath("$.items[0].overdue").value(true))
                    .andExpect(jsonPath("$.items[0].daysOverdue").value(1))
                    .andExpect(jsonPath("$.items[0].dueDate").value("2026-09-03"))
                    .andExpect(jsonPath("$.summary.overdueCount").value(1))
                    .andExpect(jsonPath("$.summary.totalBalanceDue").value(360.00));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"size=201", "size=0", "page=-1"})
        @DisplayName("400 VALIDATION_ERROR for a size outside 1-200")
        void rejectsSize(String query) throws Exception {
            mockMvc.perform(withAuth(get(OPEN_INVOICES + "?" + query), APPLY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            verify(service, never()).listOpenInvoices(any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("400 VALIDATION_ERROR for a malformed customer id")
        void rejectsMalformedId() throws Exception {
            mockMvc.perform(withAuth(get("/v1/accounting/customers/not-a-uuid/open-invoices"), APPLY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("403 ApiError without accounting:payment:apply (AC8)")
        void forbidden() throws Exception {
            mockMvc.perform(withAuth(get(OPEN_INVOICES), "accounting:analytics:view"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").exists());
            verify(service, never()).listOpenInvoices(any(), anyInt(), anyInt());
        }
    }

    @Test
    @DisplayName("both operations carry their operationId, permission and view event")
    void annotations() throws Exception {
        Method payments = ReceivablePaymentController.class.getMethod(
                "listUnappliedPayments", String.class, UUID.class, int.class, int.class);
        Method invoices = CustomerReceivablesController.class.getMethod(
                "listCustomerOpenInvoices", UUID.class, int.class, int.class);

        assertThat(payments.getAnnotation(Operation.class).operationId()).isEqualTo("listUnappliedPayments");
        assertThat(payments.getAnnotation(EmitEvent.class).id()).isEqualTo("ACCOUNTING_RECEIVABLE_PAYMENT_LIST_VIEW");
        assertThat(payments.getAnnotation(PreAuthorize.class).value()).contains(APPLY);
        assertThat(invoices.getAnnotation(Operation.class).operationId()).isEqualTo("listCustomerOpenInvoices");
        assertThat(invoices.getAnnotation(EmitEvent.class).id()).isEqualTo("ACCOUNTING_CUSTOMER_OPEN_INVOICES_VIEW");
        assertThat(invoices.getAnnotation(PreAuthorize.class).value()).contains(APPLY);
    }

    private static UnappliedPaymentsPage page() {
        SuggestedInvoice suggested = SuggestedInvoice.builder()
                .invoiceId(INVOICE_ID)
                .invoiceNumber("INV-2026-01702")
                .balanceDue(new BigDecimal("4615.00"))
                .suggestedAmount(new BigDecimal("4615.00"))
                .build();
        UnappliedPaymentRow row = UnappliedPaymentRow.builder()
                .paymentId(PAYMENT_ID)
                .customerId(CUSTOMER_ID)
                .customerDisplayName("Rivera Trucking")
                .customerReference("CUST-00412")
                .paymentMethod("CARD")
                .receivedAt(Instant.parse("2026-10-04T15:12:09Z"))
                .currency("USD")
                .totalAmount(new BigDecimal("4615.00"))
                .unappliedAmount(new BigDecimal("4615.00"))
                .sourceInvoiceId(INVOICE_ID)
                .sourceInvoiceNumber("INV-2026-01702")
                .suggestion(PaymentMatchSuggestion.builder()
                        .reasons(List.of("REMITTANCE_REFERENCE", "SAME_CUSTOMER", "EXACT_TOTAL"))
                        .invoices(List.of(suggested))
                        .suggestedTotal(new BigDecimal("4615.00"))
                        .leftOver(new BigDecimal("0.00"))
                        .build())
                .build();
        return UnappliedPaymentsPage.builder()
                .items(List.of(row))
                .page(0)
                .size(25)
                .totalElements(1)
                .totalPages(1)
                .summary(UnappliedPaymentsSummary.builder()
                        .count(1)
                        .totalUnappliedAmount(new BigDecimal("4615.00"))
                        .currency("USD")
                        .asOf(TEST_CLOCK.instant())
                        .build())
                .build();
    }

    private static CustomerOpenInvoicesPage openInvoices() {
        OpenInvoiceRow row = OpenInvoiceRow.builder()
                .invoiceId(INVOICE_ID)
                .invoiceNumber("INV-2026-01702")
                .documentDate(LocalDate.of(2026, 8, 4))
                .dueDate(LocalDate.of(2026, 9, 3))
                .total(new BigDecimal("500.00"))
                .balanceDue(new BigDecimal("360.00"))
                .arStatus("PARTIALLY_PAID")
                .overdue(true)
                .daysOverdue(1)
                .currency("USD")
                .build();
        return CustomerOpenInvoicesPage.builder()
                .items(List.of(row))
                .page(0)
                .size(100)
                .totalElements(1)
                .totalPages(1)
                .summary(OpenInvoicesSummary.builder()
                        .count(1)
                        .totalBalanceDue(new BigDecimal("360.00"))
                        .overdueCount(1)
                        .overdueBalanceDue(new BigDecimal("360.00"))
                        .currency("USD")
                        .asOf(TEST_CLOCK.instant())
                        .build())
                .build();
    }
}
