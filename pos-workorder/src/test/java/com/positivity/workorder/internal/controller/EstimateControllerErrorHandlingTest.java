package com.positivity.workorder.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import com.positivity.workorder.internal.config.GlobalExceptionHandler;
import com.positivity.workorder.internal.exception.EstimateNotFoundException;
import com.positivity.workorder.internal.service.EstimateService;
import com.positivity.workorder.internal.service.IdempotencyService;
import com.positivity.workorder.internal.service.WorkorderService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Issue #1713 (part 3), pos-workorder. {@code EstimateServiceImpl} threw
 * {@code jakarta.persistence.EntityNotFoundException} — a JPA framework type that nothing in the
 * module's advice maps — for a missing estimate. Every REST call site papered over it with a
 * {@code catch (EntityNotFoundException)} that answered a <em>bodiless</em> 404: no {@code code},
 * no {@code message}, no {@code correlationId}, in breach of ADR-0017 §3/§4, and one refactor
 * away from the platform 500 the moment a call site lost its catch.
 *
 * <p>The throws are now the module's own {@link EstimateNotFoundException} (and
 * {@code EstimateItemNotFoundException} for a missing line), which
 * {@link GlobalExceptionHandler} already maps to an enveloped, correlated 404 — the same shape
 * {@code addEstimateItem} has answered since #1694.
 */
@WebMvcTest(EstimateController.class)
@Import(WebCommonErrorAutoConfiguration.class)
@DisplayName("Estimate endpoints answer a missing estimate with the ApiError envelope (#1713)")
class EstimateControllerErrorHandlingTest {

    private static final UUID ESTIMATE_ID = UUID.fromString("019200aa-0000-7000-8000-000000000301");
    private static final String URL = "/v1/workorders/estimates/{estimateId}/approval";

    private static final String BODY = """
            {"customerId":"019200aa-0000-7000-8000-000000000302",
             "signatureData":"base64-signature",
             "signatureMimeType":"image/png",
             "signerName":"Jane Customer"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EstimateService estimateService;

    @MockitoBean
    private WorkorderService workorderService;

    @MockitoBean
    private IdempotencyService idempotencyService;

    @Test
    @WithMockUser(authorities = "workorder:estimate:approve")
    @DisplayName("approving a missing estimate answers an enveloped, correlated 404")
    void approvingAMissingEstimateAnswersEnvelopedNotFound() throws Exception {
        when(estimateService.approveEstimate(
                        any(UUID.class), any(UUID.class), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new EstimateNotFoundException(ESTIMATE_ID));

        mockMvc.perform(post(URL, ESTIMATE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ESTIMATE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Estimate not found: " + ESTIMATE_ID))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    /**
     * The regression guard for the removed {@code catch (EntityNotFoundException)}: that catch
     * converted <em>any</em> JPA {@code EntityNotFoundException} — including one Hibernate raises
     * for a broken lazy proxy, a genuine server-side defect — into a fabricated, bodiless 404.
     * With the catch gone and nothing in the module advice mapping the framework type, it now
     * reaches pos-web-common's platform catch-all as a generic, correlated 500 that echoes
     * nothing (ADR-0056 §1, the same principle as #1694).
     */
    @Test
    @WithMockUser(authorities = "workorder:estimate:approve")
    @DisplayName("a JPA EntityNotFoundException answers a generic correlated 500, not a fabricated 404")
    void aJpaEntityNotFoundExceptionAnswersGeneric500() throws Exception {
        when(estimateService.approveEstimate(
                        any(UUID.class), any(UUID.class), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new jakarta.persistence.EntityNotFoundException(
                        "Unable to find com.positivity.workorder.internal.entity.Estimate with id " + ESTIMATE_ID));

        mockMvc.perform(post(URL, ESTIMATE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }
}
