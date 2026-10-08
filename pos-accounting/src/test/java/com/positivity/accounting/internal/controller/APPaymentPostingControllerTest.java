package com.positivity.accounting.internal.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.APPaymentResponse;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.service.APPaymentService;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The AP pay command and {@code gl-posting-retry} at the HTTP edge (CAP:550 S42, #2603, #2627): the permission matrix,
 * the request contract ({@code bankAccountId}, {@code overrideJustification}, no {@code netAmount}) and every code
 * the story lists.
 */
@DisplayName("AP payment posting endpoints (S42, #2603)")
class APPaymentPostingControllerTest extends BaseIntegrationTest {

    private static final String PAY = "/v1/accounting/ap/payments";
    private static final String RETRY = "/v1/accounting/ap/payments/{paymentId}/gl-posting-retry";
    private static final UUID PAYMENT_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f7001");
    private static final UUID BANK = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f1000");

    @MockitoBean
    private APPaymentService apPaymentService;

    /** The real emission, observed: AC6 asks that the retry writes ACCOUNTING_AP_PAYMENT_GL_POSTING_RETRY. */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.positivity.events.internal.service.EventEmissionService eventEmission;

    private static String payBody(String extra) {
        return """
                {"vendorId":"0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f5001","grossAmount":412.00,"feeAmount":1.50,\
                "currency":"USD","paymentRef":"PAY-412","paymentMethod":"ACH"%s}""".formatted(extra);
    }

    private static APPaymentResponse payment(APPaymentStatus status) {
        return APPaymentResponse.builder()
                .paymentId(PAYMENT_ID)
                .paymentRef("PAY-412")
                .bankAccountId(BANK)
                .paymentDate(LocalDate.of(2026, 10, 8))
                .status(status)
                .allocations(List.of())
                .build();
    }

    @Nested
    @DisplayName("POST /v1/accounting/ap/payments")
    class Pay {

        @Test
        @DisplayName("accounting:ap:pay is required: without it 403, the service never called")
        void needsApPay() throws Exception {
            mockMvc.perform(withAuth(post(PAY), "accounting:je:post,accounting:period:override")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payBody("")))
                    .andExpect(status().isForbidden());
            verify(apPaymentService, never()).executePayment(any(), any());
        }

        @Test
        @DisplayName("201 with bankAccountId and paymentDate; netAmount is not in the response")
        void paysFromTheBankAccount() throws Exception {
            when(apPaymentService.getPaymentByRef("PAY-412")).thenReturn(Optional.empty());
            when(apPaymentService.executePayment(any(), eq(TEST_USER)))
                    .thenReturn(payment(APPaymentStatus.GL_POST_PENDING));

            mockMvc.perform(withAuth(post(PAY), "accounting:ap:pay")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payBody(",\"bankAccountId\":\"" + BANK + "\"")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.bankAccountId").value(BANK.toString()))
                    .andExpect(jsonPath("$.paymentDate").value("2026-10-08"))
                    .andExpect(jsonPath("$.netAmount").doesNotExist());
            verify(apPaymentService)
                    .executePayment(
                            org.mockito.ArgumentMatchers.argThat(request -> BANK.equals(request.getBankAccountId())),
                            eq(TEST_USER));
        }

        @Test
        @DisplayName("an overrideJustification under 10 characters is 400, before the service")
        void shortOverrideJustification() throws Exception {
            mockMvc.perform(withAuth(post(PAY), "accounting:ap:pay,accounting:period:override")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payBody(",\"overrideJustification\":\"too short\"")))
                    .andExpect(status().isBadRequest());
            verify(apPaymentService, never()).executePayment(any(), any());
        }

        @Test
        @DisplayName("1a: 422 AP_PAYMENT_METHOD_NOT_SUPPORTED")
        void methodNotSupported() throws Exception {
            refusedWith(
                    new VendorBillException(
                            VendorBillException.Code.AP_PAYMENT_METHOD_NOT_SUPPORTED, "card payments not booked yet"),
                    422,
                    "AP_PAYMENT_METHOD_NOT_SUPPORTED");
        }

        @Test
        @DisplayName("1b: 422 CURRENCY_NOT_SUPPORTED")
        void currencyNotSupported() throws Exception {
            refusedWith(new CurrencyNotSupportedException("EUR is not booked"), 422, "CURRENCY_NOT_SUPPORTED");
        }

        @Test
        @DisplayName("1c: 400 VALIDATION_ERROR with fieldErrors[bankAccountId]")
        void bankAccountRequired() throws Exception {
            String message = "bankAccountId is required: 2 active USD bank accounts (BANK_CASH) could pay; choose one";
            when(apPaymentService.getPaymentByRef("PAY-412")).thenReturn(Optional.empty());
            when(apPaymentService.executePayment(any(), any()))
                    .thenThrow(new VendorBillException(
                            VendorBillException.Code.VALIDATION_ERROR,
                            message,
                            List.of(new VendorBillException.FieldError("bankAccountId", message)),
                            null));

            mockMvc.perform(withAuth(post(PAY), "accounting:ap:pay")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payBody("")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("bankAccountId")));
        }

        @Test
        @DisplayName("5c, 5d: 422 PERIOD_CLOSED and GL_MAPPING_NOT_CONFIGURED (guided, naming AP_PAYMENT/<key>)")
        void periodAndMapping() throws Exception {
            refusedWith(new AccountingPeriodClosedException("2026-10", "closed"), 422, "PERIOD_CLOSED");
            org.mockito.Mockito.doThrow(new GLMappingNotConfiguredException(
                            "No active AP_PAYMENT/PAYMENT_FEES mapping",
                            "AP_PAYMENT",
                            "PAYMENT_FEES",
                            "Set up the AP_PAYMENT/PAYMENT_FEES GL mapping, then pay again."))
                    .when(apPaymentService)
                    .executePayment(any(), any());
            mockMvc.perform(withAuth(post(PAY), "accounting:ap:pay")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payBody("")))
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.code").value("GL_MAPPING_NOT_CONFIGURED"))
                    .andExpect(jsonPath("$.referenceId").value("AP_PAYMENT/PAYMENT_FEES"));
        }

        @Test
        @DisplayName("#2627: a lock wait beyond accounting.ap.lock-timeout is 409 LOCK_TIMEOUT")
        void lockTimeout() throws Exception {
            refusedWith(new CannotAcquireLockException("canceling statement due to lock timeout"), 409, "LOCK_TIMEOUT");
            mockMvc.perform(withAuth(post(PAY), "accounting:ap:pay")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payBody("")))
                    .andExpect(jsonPath("$.message").value("Another request is working on these bills; retry"));
        }

        private void refusedWith(RuntimeException refusal, int status, String code) throws Exception {
            when(apPaymentService.getPaymentByRef("PAY-412")).thenReturn(Optional.empty());
            org.mockito.Mockito.doThrow(refusal).when(apPaymentService).executePayment(any(), any());

            mockMvc.perform(withAuth(post(PAY), "accounting:ap:pay")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payBody("")))
                    .andExpect(status().is(status))
                    .andExpect(jsonPath("$.code").value(code));
        }
    }

    @Nested
    @DisplayName("POST /v1/accounting/ap/payments/{paymentId}/gl-posting-retry")
    class Retry {

        @Test
        @DisplayName("accounting:je:post is required: accounting:ap:pay or accounting:period:override alone is 403")
        void needsJePost() throws Exception {
            for (String authorities :
                    List.of("accounting:ap:pay", "accounting:period:override", "accounting:ap:view")) {
                mockMvc.perform(withAuth(post(RETRY, PAYMENT_ID), authorities)).andExpect(status().isForbidden());
            }
            verify(apPaymentService, never()).retryGLPosting(any(), any());
        }

        @Test
        @DisplayName("200 with no body: the payment GL_POSTED")
        void postsWithoutABody() throws Throwable {
            when(apPaymentService.retryGLPosting(PAYMENT_ID, null)).thenReturn(payment(APPaymentStatus.GL_POSTED));

            mockMvc.perform(withAuth(post(RETRY, PAYMENT_ID), "accounting:je:post"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("GL_POSTED"));
            verify(apPaymentService).retryGLPosting(eq(PAYMENT_ID), isNull());
            // AC6: the retry writes its audit event through the platform's emission service.
            verify(eventEmission)
                    .executeWithEventEmission(eq("ACCOUNTING_AP_PAYMENT_GL_POSTING_RETRY"), eq("1"), any());
        }

        @Test
        @DisplayName("the caller's overrideJustification reaches the service; under 10 characters is 400")
        void overrideJustification() throws Exception {
            when(apPaymentService.retryGLPosting(PAYMENT_ID, "Reopened June for the audit"))
                    .thenReturn(payment(APPaymentStatus.GL_POSTED));

            mockMvc.perform(withAuth(post(RETRY, PAYMENT_ID), "accounting:je:post,accounting:period:override")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"overrideJustification\":\"Reopened June for the audit\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(withAuth(post(RETRY, PAYMENT_ID), "accounting:je:post,accounting:period:override")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"overrideJustification\":\"short\"}"))
                    .andExpect(status().isBadRequest());
            verify(apPaymentService).retryGLPosting(PAYMENT_ID, "Reopened June for the audit");
        }

        @Test
        @DisplayName("404 NOT_FOUND, 409 AP_PAYMENT_NOT_RETRYABLE, 409 LOCK_TIMEOUT, 422 GL_MAPPING_NOT_CONFIGURED")
        void codes() throws Exception {
            retried(new EntityNotFoundException("AP payment not found"), 404, "NOT_FOUND");
            retried(
                    new VendorBillException(VendorBillException.Code.AP_PAYMENT_NOT_RETRYABLE, "already posted"),
                    409,
                    "AP_PAYMENT_NOT_RETRYABLE");
            retried(new CannotAcquireLockException("lock timeout"), 409, "LOCK_TIMEOUT");
            retried(new GLMappingNotConfiguredException("missing"), 422, "GL_MAPPING_NOT_CONFIGURED");
            retried(new AccountingPeriodClosedException("2026-09", "closed"), 422, "PERIOD_CLOSED");
        }

        private void retried(RuntimeException failure, int status, String code) throws Exception {
            org.mockito.Mockito.doThrow(failure).when(apPaymentService).retryGLPosting(any(), any());
            mockMvc.perform(withAuth(post(RETRY, PAYMENT_ID), "accounting:je:post"))
                    .andExpect(status().is(status))
                    .andExpect(jsonPath("$.code").value(code));
        }
    }

    @Test
    @DisplayName("AC10: the request and response schemas carry bankAccountId and no netAmount; the retry is documented")
    void openApiContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ExecuteAPPaymentRequest.properties.bankAccountId")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ExecuteAPPaymentRequest.properties.overrideJustification")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ExecuteAPPaymentRequest.properties.netAmount")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.APPaymentResponse.properties.netAmount")
                        .doesNotExist())
                .andExpect(
                        jsonPath("$.paths['/v1/accounting/ap/payments/{paymentId}/gl-posting-retry'].post.operationId")
                                .value("retryApPaymentGlPosting"));
    }
}
