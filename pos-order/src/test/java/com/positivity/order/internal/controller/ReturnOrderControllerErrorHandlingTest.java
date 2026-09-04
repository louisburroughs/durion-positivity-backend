package com.positivity.order.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.BaseContractIntegrationTest;
import com.positivity.order.internal.exception.ReturnLineNotReturnableException;
import com.positivity.order.internal.exception.ReturnRequestValidationException;
import com.positivity.order.internal.service.ReturnOrderService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Issue #1694: {@link ReturnOrderExceptionHandler} no longer has a blanket
 * {@code @ExceptionHandler(IllegalArgumentException.class)}. The former single 422
 * {@code RETURN_INVALID_ARGUMENT} catch-all is now split into {@link ReturnRequestValidationException}
 * (400 — malformed request, per ADR-0017) and {@link ReturnLineNotReturnableException} (422 — a new
 * {@code RETURN_LINE_NOT_RETURNABLE} code for a well-formed request the domain refuses on its
 * merits). A bare {@code IllegalArgumentException} is no longer caught by this module's advice and
 * falls through to {@code pos-web-common}'s {@code GlobalApiExceptionHandler}, which answers a
 * generic, correlated 500 that never echoes the exception's own text.
 *
 * <p>Uses the module's full-context integration pattern ({@link BaseContractIntegrationTest}) —
 * see {@link RegisterSessionControllerErrorHandlingTest} for why a {@code @WebMvcTest} slice does
 * not work here.
 */
@DisplayName("Return-order endpoints answer the errors they document (#1694)")
class ReturnOrderControllerErrorHandlingTest extends BaseContractIntegrationTest {

    private static final UUID RETURN_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b03");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReturnOrderService returnOrderService;

    @Test
    @DisplayName("a request validation failure answers 400 with its own message and code")
    void aRequestValidationFailureAnswers400WithItsOwnMessageAndCode() throws Exception {
        when(returnOrderService.getReturn(any()))
                .thenThrow(new ReturnRequestValidationException("A return must have at least one line"));

        mockMvc.perform(withGatewayAuth(get("/v1/returns/{returnOrderId}", RETURN_ID), "order:return:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RETURN_INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("A return must have at least one line"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("a not-returnable line answers 422 with its own new code")
    void aNotReturnableLineAnswers422WithItsOwnNewCode() throws Exception {
        when(returnOrderService.getReturn(any())).thenThrow(new ReturnLineNotReturnableException(RETURN_ID));

        mockMvc.perform(withGatewayAuth(get("/v1/returns/{returnOrderId}", RETURN_ID), "order:return:view"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RETURN_LINE_NOT_RETURNABLE"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("an unexpected IllegalArgumentException answers 500 without leaking its message")
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'returnQty'";
        when(returnOrderService.getReturn(any())).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(
                        withGatewayAuth(get("/v1/returns/{returnOrderId}", RETURN_ID), "order:return:view"))
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
