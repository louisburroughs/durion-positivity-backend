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
import com.positivity.accounting.internal.dto.APPaymentResponse;
import com.positivity.accounting.internal.dto.RecordAPPaymentReturnRequest;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.service.APPaymentService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("POST /v1/accounting/ap/payments/{paymentId}/record-return (#2642)")
class APPaymentReturnControllerTest extends BaseIntegrationTest {

    private static final String RETURN = "/v1/accounting/ap/payments/{paymentId}/record-return";
    private static final UUID PAYMENT_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f7001");
    private static final String BODY = """
            {"requestId":"0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f9001","returnDate":"2026-10-15","failureCode":"R01",\
            "justification":"Bank statement 2026-10-15: ACH return R01 (insufficient funds) for PAY-412"}""";

    @MockitoBean
    private APPaymentService apPaymentService;

    @Test
    @DisplayName("accounting:ap:pay is required: without it 403 and the service is never called")
    void needsApPay() throws Exception {
        mockMvc.perform(withAuth(post(RETURN, PAYMENT_ID), "accounting:ap:view,accounting:je:post")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
        verify(apPaymentService, never()).recordReturn(any(), any(), any());
    }

    @Test
    @DisplayName("200 with the returned payment; the body and the caller reach the service")
    void recordsTheReturn() throws Exception {
        when(apPaymentService.recordReturn(eq(PAYMENT_ID), any(), any()))
                .thenReturn(APPaymentResponse.builder()
                        .paymentId(PAYMENT_ID)
                        .paymentRef("PAY-412")
                        .status(APPaymentStatus.RETURNED)
                        .returnDate(LocalDate.of(2026, 10, 15))
                        .returnDateRule("RETURN_DATE")
                        .returnFailureCode("R01")
                        .allocations(List.of())
                        .build());

        mockMvc.perform(withAuth(post(RETURN, PAYMENT_ID), "accounting:ap:pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.returnDateRule").value("RETURN_DATE"))
                .andExpect(jsonPath("$.returnFailureCode").value("R01"));

        ArgumentCaptor<RecordAPPaymentReturnRequest> sent = ArgumentCaptor.forClass(RecordAPPaymentReturnRequest.class);
        verify(apPaymentService).recordReturn(eq(PAYMENT_ID), sent.capture(), any());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().getFailureCode())
                .isEqualTo("R01");
        org.assertj.core.api.Assertions.assertThat(sent.getValue().getReturnDate())
                .isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    @DisplayName(
            "400 VALIDATION_ERROR for a missing requestId, a short justification or a failureCode that is a sentence")
    void validation() throws Exception {
        String noRequestId = "{\"justification\":\"Bank statement 2026-10-15: ACH return R01 for PAY-412\"}";
        String shortJustification =
                "{\"requestId\":\"0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f9001\",\"justification\":\"returned\"}";
        String prose = "{\"requestId\":\"0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f9001\",\"failureCode\":\"insufficient funds\","
                + "\"justification\":\"Bank statement 2026-10-15: ACH return R01 for PAY-412\"}";
        for (String body : List.of(noRequestId, shortJustification, prose)) {
            mockMvc.perform(withAuth(post(RETURN, PAYMENT_ID), "accounting:ap:pay")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(apPaymentService, never()).recordReturn(any(), any(), any());
    }

    @Test
    @DisplayName("409 AP_PAYMENT_NOT_RETURNABLE with nextAction, 409 AP_PAYMENT_ALREADY_RETURNED")
    void refusals() throws Exception {
        org.mockito.Mockito.doThrow(new VendorBillException(
                        VendorBillException.Code.AP_PAYMENT_NOT_RETURNABLE,
                        "AP payment PAY-412 is GATEWAY_PENDING",
                        List.of(),
                        "Reconcile it with the gateway first (reconcileApPaymentGateway)"))
                .when(apPaymentService)
                .recordReturn(any(), any(), any());
        mockMvc.perform(withAuth(post(RETURN, PAYMENT_ID), "accounting:ap:pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AP_PAYMENT_NOT_RETURNABLE"))
                .andExpect(jsonPath("$.nextAction")
                        .value("Reconcile it with the gateway first (reconcileApPaymentGateway)"));

        org.mockito.Mockito.doThrow(new VendorBillException(
                        VendorBillException.Code.AP_PAYMENT_ALREADY_RETURNED, "AP payment PAY-412 is already RETURNED"))
                .when(apPaymentService)
                .recordReturn(any(), any(), any());
        mockMvc.perform(withAuth(post(RETURN, PAYMENT_ID), "accounting:ap:pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AP_PAYMENT_ALREADY_RETURNED"));
    }

    @Test
    @DisplayName("GET /v1/accounting/ap/payments/in-flight needs accounting:ap:view and answers the list")
    void inFlightRead() throws Exception {
        String inFlight = "/v1/accounting/ap/payments/in-flight";
        mockMvc.perform(withAuth(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(inFlight),
                        "accounting:ap:pay"))
                .andExpect(status().isForbidden());
        when(apPaymentService.listInFlight())
                .thenReturn(List.of(com.positivity.accounting.internal.dto.APPaymentInFlightResponse.builder()
                        .paymentId(PAYMENT_ID)
                        .paymentRef("PAY-412")
                        .status(APPaymentStatus.GL_POSTED)
                        .ageDays(9)
                        .aged(true)
                        .build()));
        mockMvc.perform(withAuth(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(inFlight),
                        "accounting:ap:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentRef").value("PAY-412"))
                .andExpect(jsonPath("$[0].aged").value(true));
    }
}
