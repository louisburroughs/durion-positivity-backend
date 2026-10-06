package com.positivity.accounting.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseControllerSliceTest;
import com.positivity.accounting.internal.dto.UnpaidWalkInSalesResponse;
import com.positivity.accounting.internal.dto.WalkInNeedsAttention;
import com.positivity.accounting.internal.dto.WalkInOpenInvoice;
import com.positivity.accounting.internal.dto.WalkInUnappliedPayment;
import com.positivity.accounting.internal.enums.WalkInResolution;
import com.positivity.accounting.internal.service.UnpaidWalkInSalesService;
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
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The unpaid walk-in sales read of #2508 through the production security chain: 200 for a holder of
 * {@code reporting:view:financial-statements}, 403 otherwise, and its OpenAPI and event annotations.
 */
@DisplayName("UnpaidWalkInSalesController (#2508)")
@WebMvcTest(UnpaidWalkInSalesController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class UnpaidWalkInSalesControllerTest extends BaseControllerSliceTest {

    private static final String VIEW = "reporting:view:financial-statements";
    private static final String PATH = "/v1/accounting/unpaid-walk-in-sales";
    private static final UUID INVOICE_ID = UUID.fromString("0199a000-0000-7000-8000-000000001702");
    private static final UUID PAYMENT_ID = UUID.fromString("0199a000-0000-7000-8000-000000000101");

    @MockitoBean
    private UnpaidWalkInSalesService service;

    @Test
    @DisplayName("200 with the balance, open invoices, needs-attention item and unapplied payments")
    void reads() throws Exception {
        when(service.read())
                .thenReturn(UnpaidWalkInSalesResponse.builder()
                        .asOf(Instant.parse("2026-10-06T15:00:00Z"))
                        .houseAccountKnown(true)
                        .customerNumber("CASH")
                        .currencyCode("USD")
                        .balance(new BigDecimal("40.00"))
                        .openInvoices(List.of(WalkInOpenInvoice.builder()
                                .invoiceNumber("INV-1042")
                                .locationCode("LOC-107")
                                .saleDate(LocalDate.of(2026, 10, 5))
                                .total(new BigDecimal("40.00"))
                                .balanceDue(new BigDecimal("40.00"))
                                .businessDayEnded(true)
                                .timezoneFallback(false)
                                .resolutions(List.of(WalkInResolution.COLLECT, WalkInResolution.CREDIT_MEMO))
                                .invoiceId(INVOICE_ID)
                                .build()))
                        .needsAttention(new WalkInNeedsAttention(1, new BigDecimal("40.00")))
                        .unappliedPayments(List.of(WalkInUnappliedPayment.builder()
                                .paymentReference("INV-1041")
                                .receivedAt(Instant.parse("2026-10-05T15:12:09Z"))
                                .unappliedAmount(new BigDecimal("5.00"))
                                .paymentId(PAYMENT_ID)
                                .build()))
                        .build());

        mockMvc.perform(withAuth(get(PATH), VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.houseAccountKnown").value(true))
                .andExpect(jsonPath("$.customerNumber").value("CASH"))
                .andExpect(jsonPath("$.balance").value(40.00))
                .andExpect(jsonPath("$.openInvoices[0].invoiceNumber").value("INV-1042"))
                .andExpect(jsonPath("$.openInvoices[0].saleDate").value("2026-10-05"))
                .andExpect(jsonPath("$.openInvoices[0].businessDayEnded").value(true))
                .andExpect(jsonPath("$.openInvoices[0].resolutions[0]").value("COLLECT"))
                .andExpect(jsonPath("$.openInvoices[0].resolutions[1]").value("CREDIT_MEMO"))
                .andExpect(jsonPath("$.openInvoices[0].resolutions.length()").value(2))
                .andExpect(jsonPath("$.needsAttention.count").value(1))
                .andExpect(jsonPath("$.unappliedPayments[0].paymentReference").value("INV-1041"))
                .andExpect(jsonPath("$.unappliedPayments[0].unappliedAmount").value(5.00));
    }

    @Test
    @DisplayName("403 without reporting:view:financial-statements")
    void forbidden() throws Exception {
        mockMvc.perform(withAuth(get(PATH), "accounting:payment:apply")).andExpect(status().isForbidden());

        verify(service, never()).read();
    }

    @Test
    @DisplayName("operation id, permission and the ACCOUNTING_UNPAID_WALK_IN_SALES_VIEW event")
    void annotations() throws Exception {
        Method read = UnpaidWalkInSalesController.class.getMethod("getUnpaidWalkInSales");

        assertThat(read.getAnnotation(Operation.class).operationId()).isEqualTo("getUnpaidWalkInSales");
        assertThat(read.getAnnotation(EmitEvent.class).id()).isEqualTo("ACCOUNTING_UNPAID_WALK_IN_SALES_VIEW");
        assertThat(read.getAnnotation(PreAuthorize.class).value()).contains(VIEW);
    }
}
