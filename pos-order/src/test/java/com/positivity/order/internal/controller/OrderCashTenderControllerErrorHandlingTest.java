package com.positivity.order.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.BaseControllerSliceTest;
import com.positivity.order.internal.dto.CashTenderResponse;
import com.positivity.order.internal.exception.CartIdempotencyConflictException;
import com.positivity.order.internal.exception.CashTenderRefusedException;
import com.positivity.order.internal.exception.InvoicingUnavailableException;
import com.positivity.order.internal.exception.OrderConflictException;
import com.positivity.order.internal.service.CashTenderService;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.shared.error.ApiError;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** ADR-0067 A8 (#2662): the cash tender endpoint answers the codes it documents (ADR-0017). */
@DisplayName("register cash tender endpoint answers the errors it documents (#2662)")
@WebMvcTest(OrderCashTenderController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class OrderCashTenderControllerErrorHandlingTest extends BaseControllerSliceTest {

    private static final UUID ORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c01");
    private static final String BODY = "{\"cashTendered\":20.00,\"currencyCode\":\"CAD\"}";

    @MockitoBean
    private CashTenderService cashTenderService;

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder tender() {
        return withGatewayAuth(
                post("/v1/orders/{orderId}/payments/cash", ORDER_ID)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY),
                "order:order:checkout");
    }

    @Test
    void recordsAndAnswers201WithTheFigures() throws Exception {
        when(cashTenderService.recordCashTender(eq(ORDER_ID), anyString(), any()))
                .thenReturn(new CashTenderResponse(
                        ORDER_ID,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new BigDecimal("9.98"),
                        new BigDecimal("0.05"),
                        new BigDecimal("10.00"),
                        new BigDecimal("0.02"),
                        new BigDecimal("20.00"),
                        new BigDecimal("10.00"),
                        "CAD",
                        BigDecimal.ZERO));

        mockMvc.perform(tender())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cashDue").value(10.00))
                .andExpect(jsonPath("$.cashRoundingAmount").value(0.02))
                .andExpect(jsonPath("$.changeDue").value(10.00));
    }

    @Test
    void aMissingIdempotencyKeyIs400() throws Exception {
        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/{orderId}/payments/cash", ORDER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY),
                        "order:order:checkout"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORDER_INVALID_ARGUMENT"));
    }

    @Test
    void anInsufficientTenderIs422WithTheFieldError() throws Exception {
        when(cashTenderService.recordCashTender(any(), anyString(), any()))
                .thenThrow(new CashTenderRefusedException(
                        CashTenderRefusedException.Code.CASH_TENDER_INSUFFICIENT,
                        "cashTendered 9.95 is below the cash due 10.00",
                        List.of(new ApiError.FieldError("cashTendered", "cashDue 10.00"))));

        mockMvc.perform(tender())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CASH_TENDER_INSUFFICIENT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cashTendered"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void aReusedKeyIs409IdempotencyConflict() throws Exception {
        when(cashTenderService.recordCashTender(any(), anyString(), any()))
                .thenThrow(new CartIdempotencyConflictException("reused"));

        mockMvc.perform(tender())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void aBalanceDisagreementIs409OrderConflict() throws Exception {
        when(cashTenderService.recordCashTender(any(), anyString(), any()))
                .thenThrow(new OrderConflictException("stale"));

        mockMvc.perform(tender())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_CONFLICT"));
    }

    @Test
    void anInvoicingOutageIs503WithRetryAfter() throws Exception {
        when(cashTenderService.recordCashTender(any(), anyString(), any()))
                .thenThrow(new InvoicingUnavailableException("down"));

        mockMvc.perform(tender())
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("ORDER_INVOICING_UNAVAILABLE"));
    }

    @Test
    void withoutTheCheckoutAuthorityIs403() throws Exception {
        mockMvc.perform(withGatewayAuth(
                        post("/v1/orders/{orderId}/payments/cash", ORDER_ID)
                                .header("Idempotency-Key", "key-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY),
                        "order:order:view"))
                .andExpect(status().isForbidden());
    }
}
