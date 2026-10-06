package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.RemainderCreditResponse;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.PaymentNotAvailableException;
import com.positivity.accounting.internal.exception.PaymentNotFoundException;
import com.positivity.accounting.internal.exception.PaymentRemainderChangedException;
import com.positivity.accounting.internal.service.PaymentApplicationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * WebMvc tests for {@code POST /v1/accounting/payments/{paymentId}/remainder-credit} (CAP:550 S35,
 * #2524): every listed status and code, and the 403 for a caller without
 * {@code accounting:payment:apply}.
 */
@DisplayName("PaymentApplicationController remainder-credit (#2524)")
class PaymentRemainderCreditControllerTest extends BaseIntegrationTest {

    private static final UUID PAYMENT_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000bb");
    private static final String PATH = "/v1/accounting/payments/" + PAYMENT_ID + "/remainder-credit";
    private static final String BODY = "{\"requestId\":\"remainder-1\",\"expectedAmount\":12.50}";

    @MockitoBean
    private PaymentApplicationService paymentApplicationService;

    @Test
    @DisplayName("201 with the credit for a holder of accounting:payment:apply")
    void creditsRemainder() throws Exception {
        UUID creditId = UUID.fromString("018f0000-0000-7000-8000-0000000000cc");
        when(paymentApplicationService.creditPaymentRemainder(eq(PAYMENT_ID), any()))
                .thenReturn(RemainderCreditResponse.builder()
                        .paymentId(PAYMENT_ID)
                        .requestId("remainder-1")
                        .creditId(creditId)
                        .amount(new BigDecimal("12.50"))
                        .currency("USD")
                        .remainingAmount(BigDecimal.ZERO)
                        .createdAt(Instant.parse("2026-10-06T08:00:00Z"))
                        .build());

        mockMvc.perform(withAuth(post(PATH), "accounting:payment:apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID.toString()))
                .andExpect(jsonPath("$.requestId").value("remainder-1"))
                .andExpect(jsonPath("$.creditId").value(creditId.toString()))
                .andExpect(jsonPath("$.amount").value(12.50))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.remainingAmount").value(0))
                .andExpect(jsonPath("$.createdAt").value("2026-10-06T08:00:00Z"));
    }

    @Test
    @DisplayName("AC11: 403 for a caller holding accounting:customer-credit:apply but not accounting:payment:apply")
    void forbiddenWithoutPaymentApply() throws Exception {
        mockMvc.perform(withAuth(post(PATH), "accounting:customer-credit:apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
        verify(paymentApplicationService, never()).creditPaymentRemainder(any(), any());
    }

    @Test
    @DisplayName("400 VALIDATION_ERROR when requestId is blank or expectedAmount below 0.01")
    void validationErrors() throws Exception {
        mockMvc.perform(withAuth(post(PATH), "accounting:payment:apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"expectedAmount\":12.50}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withAuth(post(PATH), "accounting:payment:apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"remainder-1\",\"expectedAmount\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withAuth(post(PATH), "accounting:payment:apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + "x".repeat(101) + "\",\"expectedAmount\":12.50}"))
                .andExpect(status().isBadRequest());
        verify(paymentApplicationService, never()).creditPaymentRemainder(any(), any());
    }

    @Test
    @DisplayName("404 PAYMENT_NOT_FOUND")
    void paymentNotFound() throws Exception {
        when(paymentApplicationService.creditPaymentRemainder(eq(PAYMENT_ID), any()))
                .thenThrow(new PaymentNotFoundException("Payment not found: " + PAYMENT_ID));

        mockMvc.perform(withAuth(post(PATH), "accounting:payment:apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("409 PAYMENT_NOT_AVAILABLE, IDEMPOTENCY_CONFLICT and OPTIMISTIC_LOCK")
    void conflicts() throws Exception {
        when(paymentApplicationService.creditPaymentRemainder(eq(PAYMENT_ID), any()))
                .thenThrow(new PaymentNotAvailableException("fully applied"))
                .thenThrow(new IdempotencyConflictException("requestId used on another payment"))
                .thenThrow(new OptimisticLockingFailureException("modified concurrently"));

        mockMvc.perform(withAuth(post(PATH), "accounting:payment:apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_AVAILABLE"));
        mockMvc.perform(withAuth(post(PATH), "accounting:payment:apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mockMvc.perform(withAuth(post(PATH), "accounting:payment:apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
    }

    @Test
    @DisplayName("422 PAYMENT_REMAINDER_CHANGED and CURRENCY_NOT_SUPPORTED")
    void unprocessable() throws Exception {
        when(paymentApplicationService.creditPaymentRemainder(eq(PAYMENT_ID), any()))
                .thenThrow(new PaymentRemainderChangedException("remainder changed"))
                .thenThrow(new CurrencyNotSupportedException("EUR"));

        mockMvc.perform(withAuth(post(PATH), "accounting:payment:apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_REMAINDER_CHANGED"));
        mockMvc.perform(withAuth(post(PATH), "accounting:payment:apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CURRENCY_NOT_SUPPORTED"));
    }
}
