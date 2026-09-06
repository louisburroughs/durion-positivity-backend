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
import com.positivity.workorder.internal.exception.EstimateIncompleteException;
import com.positivity.workorder.internal.exception.EstimateNotFoundException;
import com.positivity.workorder.internal.exception.WorkorderResourceConflictException;
import com.positivity.workorder.internal.service.EstimateService;
import com.positivity.workorder.internal.service.IdempotencyService;
import com.positivity.workorder.internal.service.WorkorderService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;

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
 *
 * <p>Issue #1791 re-typed the {@code submitForApproval} refusals — not-DRAFT is a
 * {@link WorkorderResourceConflictException} (409), incompleteness an
 * {@link EstimateIncompleteException} (422) — and removed the controller's local
 * {@code catch (IllegalStateException)}. The cases at the bottom prove that wire mapping, and the
 * 500 the snapshot endpoint now answers for a serialization fault, in the slice CI actually runs
 * on a PR, rather than only in the Failsafe IT.
 */
@WebMvcTest(EstimateController.class)
@Import(WebCommonErrorAutoConfiguration.class)
@DisplayName("Estimate endpoints answer refusals with the ApiError envelope (#1713 and #1791)")
class EstimateControllerErrorHandlingTest {

    private static final UUID ESTIMATE_ID = UUID.fromString("019200aa-0000-7000-8000-000000000301");
    private static final String URL = "/v1/workorders/estimates/{estimateId}/approval";
    private static final String SUBMIT_URL = "/v1/workorders/estimates/{estimateId}/submit-for-approval";
    private static final String SNAPSHOT_URL = "/v1/workorders/estimates/{estimateId}/snapshots";

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

    /**
     * The 404 on this endpoint is documented as an {@code ApiError} (#1713), but the controller
     * caught the service's {@code ResponseStatusException} and answered a bodiless 404 — one status
     * with two wire shapes, which is the mismatch a review pass rejected on
     * {@code BillingRulesController} in this same PR.
     */
    @Test
    @WithMockUser(authorities = "workorder:estimate:update")
    @DisplayName("adding an item to a missing estimate answers an enveloped, correlated 404")
    void addingAnItemToAMissingEstimateAnswersEnvelopedNotFound() throws Exception {
        when(estimateService.addEstimateItem(any(UUID.class), any(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Estimate not found"));

        mockMvc.perform(post("/v1/workorders/estimates/{estimateId}/items", ESTIMATE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemType":"LABOR","description":"Brake inspection","quantity":1,\
                                "unitPrice":129.99,"taxCode":"LABOR_STANDARD"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ESTIMATE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    /**
     * #1791: with the controller's {@code catch (IllegalStateException)} gone, the not-DRAFT
     * refusal must reach the module advice as the typed conflict and answer an enveloped 409 —
     * not a bodiless status, and not the platform 500.
     */
    @Test
    @WithMockUser(authorities = "workorder:estimate:submit")
    @DisplayName("submitting a non-DRAFT estimate answers an enveloped, correlated 409 CONFLICT")
    void submittingANonDraftEstimateAnswersEnvelopedConflict() throws Exception {
        String message = "Cannot submit estimate - must be in DRAFT state, current state: APPROVED";
        when(estimateService.submitForApproval(any(UUID.class), anyString()))
                .thenThrow(new WorkorderResourceConflictException(message));

        mockMvc.perform(post(SUBMIT_URL, ESTIMATE_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    /**
     * #1791: a DRAFT estimate that is not ready is a different refusal from the status guard
     * above and must answer 422 {@code ESTIMATE_INCOMPLETE} (ADR-0017 §2), not the 409 the
     * removed catch used to fabricate for every {@code IllegalStateException}.
     */
    @Test
    @WithMockUser(authorities = "workorder:estimate:submit")
    @DisplayName("submitting an incomplete DRAFT estimate answers an enveloped, correlated 422 ESTIMATE_INCOMPLETE")
    void submittingAnIncompleteEstimateAnswersEnvelopedUnprocessable() throws Exception {
        String message = "Cannot submit estimate - no line items added";
        when(estimateService.submitForApproval(any(UUID.class), anyString()))
                .thenThrow(new EstimateIncompleteException(message));

        mockMvc.perform(post(SUBMIT_URL, ESTIMATE_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ESTIMATE_INCOMPLETE"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    /**
     * #1791 (review): a snapshot serialization failure used to be wrapped in
     * {@code IllegalStateException}, which this endpoint's local catch answered as a bodiless 409.
     * The service now lets the raw {@code JacksonException} out and the catch is gone, so a server
     * fault answers the platform's correlated 500, the same way the JPA case above does.
     */
    @Test
    @WithMockUser(authorities = "workorder:estimate_snapshot:create")
    @DisplayName("a snapshot serialization fault answers a generic correlated 500, not a bodiless 409")
    void aSnapshotSerializationFaultAnswersGeneric500() throws Exception {
        when(estimateService.createEstimateSnapshot(any(UUID.class), anyString(), any()))
                .thenThrow(new JacksonException("cannot serialize estimate") {});

        mockMvc.perform(post(SNAPSHOT_URL, ESTIMATE_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }
}
