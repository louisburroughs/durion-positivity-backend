package com.positivity.invoice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.invoice.internal.exception.PaymentDeclinedException;
import com.positivity.invoice.internal.service.PaymentService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end proof for issue #1694, exercised through {@link PaymentController}: the blanket
 * {@code @ExceptionHandler(IllegalArgumentException.class)} that used to live in {@link
 * PaymentExceptionHandler} (400 {@code BAD_REQUEST}) is deleted outright, with no replacement
 * mapping — {@code PaymentServiceImpl} throws no {@code IllegalArgumentException} reachable
 * through this controller (every genuine field-validation 400 is already produced by bean
 * validation on {@code InitiatePaymentRequest}), so the blanket only ever existed to catch
 * whatever {@code IllegalArgumentException} a bug might throw (e.g. Hibernate/JPA) and
 * mis-report it as a client 400. This test proves a bare {@code IllegalArgumentException} from
 * the service now falls through to pos-web-common's platform-wide {@code
 * GlobalApiExceptionHandler}, which answers a generic, correlated 500 that never echoes the
 * exception's own text — and that this module's own domain exceptions (unaffected by the
 * deletion) still answer their documented status/code.
 *
 * <p>A {@code @WebMvcTest} slice does not work for this module: {@code PosInvoiceApplication}
 * declares {@code @EnableJpaRepositories} directly, which a web slice cannot exclude and which
 * then fails to find an {@code entityManagerFactory} bean. So this follows the module's existing
 * full-context pattern (see {@code OrderInvoiceContractBehaviorIT}) instead, authenticating via
 * the gateway's {@code X-User}/{@code X-Authorities} headers rather than {@code @WithMockUser}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Payment endpoints answer the errors they document (#1694)")
class PaymentControllerErrorHandlingTest {

    private static final UUID INVOICE_ID = UUID.randomUUID();
    private static final String VALID_BODY = """
            {"paymentFlow":"SALE_CAPTURE","amount":100.00,"idempotencyKey":"idem-001","paymentToken":"tok_test"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder request) {
        return request.header("X-User", "test-user").header("X-Authorities", "*");
    }

    /** A genuine, still-mapped domain exception continues to answer its documented 422. */
    @Test
    @DisplayName("a payment decline answers 422 with its own message and code")
    void paymentDeclinedAnswers422WithItsOwnMessageAndCode() throws Exception {
        when(paymentService.initiatePayment(any(), any())).thenThrow(new PaymentDeclinedException("Card declined"));

        mockMvc.perform(withAuth(post("/v1/invoices/{invoiceId}/payments", INVOICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"))
                .andExpect(jsonPath("$.message").value("Card declined"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    /**
     * The regression this test guards against: a bare {@code IllegalArgumentException} from the
     * service layer must NOT come back as a 4xx carrying its own message — it is an unexpected
     * server-side failure and must land on the generic, correlated 500 fallback.
     */
    @Test
    @DisplayName("an unexpected IllegalArgumentException answers 500 without leaking its message")
    void aBareIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute "
                + "'paymentToken' of 'com.positivity.invoice.internal.entity.PaymentIntent'";
        when(paymentService.initiatePayment(any(), any())).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(withAuth(post("/v1/invoices/{invoiceId}/payments", INVOICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(leakCanary).doesNotContain("UnknownPathException");
    }
}
