package com.positivity.order.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.BaseControllerSliceTest;
import com.positivity.order.internal.exception.RegisterSessionRequestValidationException;
import com.positivity.order.internal.service.RegisterSessionService;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Issue #1694: {@link RegisterSessionExceptionHandler} no longer has a blanket
 * {@code @ExceptionHandler(IllegalArgumentException.class)}. {@link RegisterSessionRequestValidationException}
 * keeps the genuine-client-error contract (now 400, not the former 422 — request-shape validation
 * per ADR-0017), while a bare {@code IllegalArgumentException} is no longer caught by this
 * module's advice and falls through to {@code pos-web-common}'s platform-wide
 * {@code GlobalApiExceptionHandler}, which answers a generic, correlated 500 that never echoes the
 * exception's own text.
 *
 * <p>A {@code @WebMvcTest} slice does not work for this module: {@code PosOrderApplication}
 * declares {@code @EnableJpaRepositories} directly (rather than relying on Spring Boot's own
 * auto-configuration), which a web slice cannot exclude and which then fails to find an
 * {@code entityManagerFactory} bean. So this follows the module's existing full-context pattern
 * ({@link BaseContractIntegrationTest}, see {@code PurchaseOrderErrorContractTest}) instead.
 */
@DisplayName("Register-session endpoints answer the errors they document (#1694)")
@WebMvcTest(RegisterSessionController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class RegisterSessionControllerErrorHandlingTest extends BaseControllerSliceTest {

    private static final UUID SESSION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b01");

    @MockitoBean
    private RegisterSessionService registerSessionService;

    @Test
    @DisplayName("a request validation failure answers 400 with its own message and code")
    void aRequestValidationFailureAnswers400WithItsOwnMessageAndCode() throws Exception {
        when(registerSessionService.getSession(any()))
                .thenThrow(new RegisterSessionRequestValidationException("Cash movement amount must be positive"));

        mockMvc.perform(withGatewayAuth(get("/v1/orders/sessions/{sessionId}", SESSION_ID), "order:session:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGISTER_SESSION_INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("Cash movement amount must be positive"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("an unexpected IllegalArgumentException answers 500 without leaking its message")
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute";
        when(registerSessionService.getSession(any())).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(
                        withGatewayAuth(get("/v1/orders/sessions/{sessionId}", SESSION_ID), "order:session:view"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(leakCanary).doesNotContain("UnknownPathException");
    }
}
