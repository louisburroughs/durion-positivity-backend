package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseControllerSliceTest;
import com.positivity.accounting.internal.exception.GLPostingException;
import com.positivity.accounting.internal.service.EventIngestionService;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proof for issue #1719: {@link GLPostingExceptionHandler} used to build its response with
 * {@code ApiError.of("GL_POSTING_FAILED", ..., null)} and set no {@code X-Correlation-Id}
 * response header, so a GL posting failure — the failure most worth tracing — answered a
 * {@code correlationId} of {@code null} that could not be tied back to a log entry.
 *
 * <p>ADR-0017 §4 requires the id in both the body and the header, echoing an inbound value and
 * generating one otherwise. These assertions mirror the ones #1694 added for
 * {@code APPaymentExceptionHandler} in {@link APPaymentControllerErrorHandlingTest}.
 *
 * <p>Exercised through {@link EventIngestionController}, which sits in the advice's
 * {@code basePackages} scope; {@link GLPostingException} propagates to the request thread in
 * production because the handler that throws it ({@code VendorBillGLPostingEventHandler}) is a
 * synchronous {@code @EventListener}.
 */
@DisplayName("GLPostingExceptionHandler Tests (issue #1719)")
@WebMvcTest(EventIngestionController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class GLPostingExceptionHandlerTest extends BaseControllerSliceTest {

    private static final String BASE_URL = "/v1/accounting/events";
    private static final String VIEW_AUTHORITY = "accounting:events:view";
    private static final String CLIENT_CORRELATION_ID = "gl-posting-correlation-id-0001";
    private static final UUID EVENT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");

    @MockitoBean
    private EventIngestionService eventIngestionService;

    @Test
    @DisplayName("A GL posting failure echoes the inbound correlation id in both the body and the response header")
    void glPostingFailureEchoesInboundCorrelationId() throws Exception {
        when(eventIngestionService.getEventById(any()))
                .thenThrow(new GLPostingException("GL posting failed for bill: " + EVENT_ID));

        mockMvc.perform(withAuth(get(BASE_URL + "/" + EVENT_ID), VIEW_AUTHORITY)
                        .header("X-Correlation-Id", CLIENT_CORRELATION_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GL_POSTING_FAILED"))
                .andExpect(jsonPath("$.correlationId").value(CLIENT_CORRELATION_ID))
                .andExpect(header().string("X-Correlation-Id", CLIENT_CORRELATION_ID));
    }

    @Test
    @DisplayName("A GL posting failure generates a correlation id when the client sent none")
    void glPostingFailureGeneratesCorrelationIdWhenAbsent() throws Exception {
        when(eventIngestionService.getEventById(any()))
                .thenThrow(new GLPostingException("GL posting failed for bill: " + EVENT_ID));

        mockMvc.perform(withAuth(get(BASE_URL + "/" + EVENT_ID), VIEW_AUTHORITY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GL_POSTING_FAILED"))
                .andExpect(jsonPath("$.correlationId").value(Matchers.matchesPattern("[0-9a-f-]{36}")))
                .andExpect(header().string("X-Correlation-Id", Matchers.matchesPattern("[0-9a-f-]{36}")));
    }
}
