package com.positivity.order.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.BaseControllerSliceTest;
import com.positivity.order.internal.exception.PriceOverrideRequestValidationException;
import com.positivity.order.internal.service.PriceOverrideService;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Issue #1694: {@link PriceOverrideExceptionHandler} no longer has a blanket
 * {@code @ExceptionHandler(IllegalArgumentException.class)}. {@link PriceOverrideRequestValidationException}
 * keeps the genuine-client-error contract (400 {@code ORDER_PRICE_OVERRIDE_BAD_REQUEST}, message
 * echoed — unchanged status, since 400 was already correct for this case), while a bare
 * {@code IllegalArgumentException} is no longer caught by this module's advice and falls through
 * to {@code pos-web-common}'s {@code GlobalApiExceptionHandler}, which answers a generic,
 * correlated 500 that never echoes the exception's own text.
 *
 * <p>Runs as a {@code @WebMvcTest} slice on {@link com.positivity.order.BaseControllerSliceTest},
 * whose Javadoc records why this module could not be sliced before #1723.
 */
@DisplayName("Price-override endpoints answer the errors they document (#1694)")
@WebMvcTest(PriceOverrideController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class PriceOverrideControllerErrorHandlingTest extends BaseControllerSliceTest {

    private static final UUID OVERRIDE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b06");

    @MockitoBean
    private PriceOverrideService priceOverrideService;

    @Test
    @DisplayName("a request validation failure answers 400 with its own message and code")
    void aRequestValidationFailureAnswers400WithItsOwnMessageAndCode() throws Exception {
        when(priceOverrideService.getOverrideById(any()))
                .thenThrow(new PriceOverrideRequestValidationException("orderId must be a UUID: not-a-uuid"));

        mockMvc.perform(withGatewayAuth(
                        get("/v1/orders/price-overrides/{overrideId}", OVERRIDE_ID), "order:price_override:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORDER_PRICE_OVERRIDE_BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("orderId must be a UUID: not-a-uuid"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("an unexpected IllegalArgumentException answers 500 without leaking its message")
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'reasonCode'";
        when(priceOverrideService.getOverrideById(any())).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(withGatewayAuth(
                        get("/v1/orders/price-overrides/{overrideId}", OVERRIDE_ID), "order:price_override:view"))
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
