package com.positivity.accounting.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseControllerSliceTest;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.InvalidBillAllocationException;
import com.positivity.accounting.internal.service.APPaymentService;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proof for issue #1694, exercised through {@link APPaymentController}: two advices overlap on
 * every controller in this module ({@link
 * com.positivity.accounting.internal.config.AccountingExceptionHandler}, unscoped, and {@link
 * APPaymentExceptionHandler}, scoped to {@code com.positivity.accounting.internal.controller} —
 * a scope that is every controller in this module, since none of them live in a subpackage).
 * Both used to carry a blanket {@code @ExceptionHandler(IllegalArgumentException.class)}; both
 * are now gone, replaced with per-type mappings audited for genuine client-vs-server origin.
 *
 * <p>Runs as a {@code @WebMvcTest} controller slice since #1723 removed the redundant
 * {@code @ComponentScan} from {@code PosAccountingApplication} that had been bypassing
 * {@code @WebMvcTest}'s controllers-only filtering. It exercises the same production
 * {@code AccountingExceptionHandler}, {@code APPaymentExceptionHandler} and {@code
 * pos-web-common} {@code GlobalApiExceptionHandler} beans the full context did, without booting
 * JPA or messaging.
 *
 * <ul>
 *   <li>{@link InvalidBillAllocationException} (mapped only in {@code APPaymentExceptionHandler})
 *       keeps its documented 400 {@code VALIDATION_ERROR} contract with its own message — proof
 *       the surviving, non-overlapping mapping wins on a controller both advices cover.
 *   <li>A bare {@code IllegalArgumentException} — what Hibernate/JPA throw for an invalid query,
 *       what {@code UUID.fromString} throws on malformed stored data — is no longer caught by
 *       either module advice: it falls through to {@code pos-web-common}'s platform-wide {@code
 *       GlobalApiExceptionHandler}, which answers a generic, correlated 500 that never echoes the
 *       exception's own text.
 *   <li>{@link IdempotencyConflictException} (also mapped only in {@code APPaymentExceptionHandler})
 *       proves ADR-0017 §4 correlation propagation: the response carries {@code correlationId} in
 *       the body AND the echoed value in the {@code X-Correlation-Id} response header.
 * </ul>
 */
@DisplayName("APPaymentController Error Handling Tests (issue #1694)")
@WebMvcTest(APPaymentController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class APPaymentControllerErrorHandlingTest extends BaseControllerSliceTest {

    private static final String CLIENT_CORRELATION_ID = "test-correlation-id-0001";

    @MockitoBean
    private APPaymentService apPaymentService;

    private static final String VALID_PAYMENT_REQUEST = """
            {"vendorId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
             "grossAmount":250.00,
             "currency":"USD",
             "paymentRef":"ap-pay-test-0001",
             "paymentMethod":"ACH"}
            """;

    /**
     * The surviving, non-overlapping mapping wins: {@link InvalidBillAllocationException} is
     * mapped only in {@code APPaymentExceptionHandler}, not the unscoped {@code
     * AccountingExceptionHandler} that also covers this controller. It keeps its documented 400
     * VALIDATION_ERROR contract with its own message.
     */
    @Test
    @DisplayName("A module validation failure answers its documented status, code and message")
    void aModuleValidationFailureAnswersItsDocumentedStatusCodeAndMessage() throws Exception {
        when(apPaymentService.getPaymentByRef(anyString())).thenReturn(Optional.empty());
        when(apPaymentService.executePayment(any(), anyString()))
                .thenThrow(new InvalidBillAllocationException("Total allocations exceed gross payment amount"));

        mockMvc.perform(withAuth(post("/v1/accounting/ap/payments"), "accounting:ap:pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYMENT_REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Total allocations exceed gross payment amount"));
    }

    /**
     * The regression this test guards against (issue #1694): a bare {@code
     * IllegalArgumentException} must NOT come back as a 400 carrying its own message now that
     * both blanket handlers are gone. It is an unexpected server-side failure, so it must land on
     * the generic, correlated 500 fallback — and the correlation id must be present in the body
     * (ADR-0017 §4).
     */
    @Test
    @DisplayName("A bare IllegalArgumentException answers 500 without leaking its message")
    void aBareIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'vendorBillId' "
                + "of 'com.positivity.accounting.internal.entity.VendorBill'";
        when(apPaymentService.getPaymentByRef(anyString())).thenReturn(Optional.empty());
        when(apPaymentService.executePayment(any(), anyString())).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(withAuth(post("/v1/accounting/ap/payments"), "accounting:ap:pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYMENT_REQUEST))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(leakCanary)
                .doesNotContain("UnknownPathException")
                .doesNotContain("vendorBillId");
    }

    /**
     * ADR-0017 §4: the correlation id supplied by the client is echoed in both the body and the
     * {@code X-Correlation-Id} response header. Uses {@link IdempotencyConflictException}, mapped
     * only in {@code APPaymentExceptionHandler} — proving that advice's fix (it previously never
     * set the header or populated the body field, passing {@code null} for every handler).
     */
    @Test
    @DisplayName("The correlation id is echoed in both the body and the response header")
    void correlationIdIsEchoedInBodyAndResponseHeader() throws Exception {
        when(apPaymentService.getPaymentByRef(anyString())).thenReturn(Optional.empty());
        when(apPaymentService.executePayment(any(), anyString()))
                .thenThrow(new IdempotencyConflictException("paymentRef exists with a different payload"));

        mockMvc.perform(withAuth(post("/v1/accounting/ap/payments"), "accounting:ap:pay")
                        .header("X-Correlation-Id", CLIENT_CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYMENT_REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.correlationId").value(CLIENT_CORRELATION_ID))
                .andExpect(header().string("X-Correlation-Id", CLIENT_CORRELATION_ID));
    }

    /**
     * Regression pin (issue #1694 review finding): {@code IllegalStateException} used to be
     * mapped in both {@code AccountingExceptionHandler} (unscoped) and this controller's
     * {@code APPaymentExceptionHandler} (scoped to this package), so which advice answered — and
     * therefore which {@code code} the client received for the same 409 — depended on Spring bean
     * registration order. {@code APPaymentExceptionHandler} no longer maps the type at all, so
     * {@code AccountingExceptionHandler#handleIllegalState} is now the only possible answerer for
     * this AP-payment endpoint. It runs the message through {@code resolveStateErrorCode}, which
     * falls through to {@code ILLEGAL_STATE} for a message matching none of the special-cased
     * journal/period phrasings — the opposite of the generic {@code CONFLICT} the deleted
     * duplicate always returned, so a regression back to bean-order-dependent behavior (or to the
     * deleted handler winning the race) would flip this code and fail the test.
     */
    @Test
    @DisplayName(
            "A state conflict deterministically answers AccountingExceptionHandler's code, not the deleted duplicate's")
    void aStateConflictAnswersTheSurvivingHandlersCodeDeterministically() throws Exception {
        when(apPaymentService.getPaymentByRef(anyString())).thenReturn(Optional.empty());
        when(apPaymentService.executePayment(any(), anyString()))
                .thenThrow(new IllegalStateException("Vendor payment gateway session is not open"));

        mockMvc.perform(withAuth(post("/v1/accounting/ap/payments"), "accounting:ap:pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYMENT_REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE"))
                .andExpect(jsonPath("$.message").value("Vendor payment gateway session is not open"));
    }
}
