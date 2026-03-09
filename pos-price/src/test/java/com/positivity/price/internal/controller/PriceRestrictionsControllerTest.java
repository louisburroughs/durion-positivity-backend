package com.positivity.price.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.price.BaseContractIntegrationTest;
import com.positivity.price.dto.RestrictionEvaluationResult;
import com.positivity.price.dto.RestrictionOverrideResponse;
import com.positivity.price.enums.RestrictionDecision;
import com.positivity.price.internal.exception.RestrictionServiceUnavailableException;
import com.positivity.price.service.RestrictionEvaluationService;
import com.positivity.price.service.RestrictionOverrideService;
import com.positivity.price.service.RestrictionRuleService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract behavior tests for {@link PriceRestrictionsController} — evaluate
 * and override endpoints.
 *
 * <p>ADR compliance:</p>
 * <ul>
 *   <li>ADR-0017: 200 for successful mutations, 400 for validation, 401/403 for
 *       auth/authz, 503 for service unavailability.</li>
 *   <li>ADR-0018: actor resolved from security context, not request body.</li>
 *   <li>ADR-0026: controller depends on service interfaces, never on impls.</li>
 * </ul>
 *
 * Issue: #43
 */
@DisplayName("PriceRestrictionsController – evaluate and override contract tests")
class PriceRestrictionsControllerTest extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RestrictionEvaluationService evaluationService;

    @MockitoBean
    private RestrictionOverrideService overrideService;

    @MockitoBean
    private RestrictionRuleService restrictionRuleService;

    /**
     * Default authorities for evaluate tests — general pricing viewer, NOT the
     * override authority. Override authority is set explicitly per test where needed.
     *
     * @return authority string forwarded via {@code X-Authorities} gateway header
     */
    @Override
    protected String defaultAuthorities() {
        return "pricing:view";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Evaluate endpoint: POST /v1/price/restrictions:evaluate
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * RRC-001: missing gateway auth headers must be rejected with 401 Unauthorized.
     *
     * <p>This test may already pass in RED because {@code @PreAuthorize("isAuthenticated()")}
     * is present on the controller method and Spring Security enforces it.</p>
     *
     * Issue: #43 (ADR-0017 §401)
     */
    @Test
    @DisplayName("RRC-001: no auth headers → 401")
    void RRC001_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/v1/price/restrictions:evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEvaluateBody()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * RRC-002: authenticated request with a valid body, service returns an ALLOW decision,
     * must respond 200 with {@code results[0].decision == "ALLOW"}.
     *
     * Issue: #43 (ADR-0017 §200)
     */
    @Test
    @DisplayName("RRC-002: valid request, service returns ALLOW → 200 with ALLOW decision")
    void RRC002_authenticated_validRequest_serviceReturnsAllow_returns200() throws Exception {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(evaluationService.evaluate(any()))
                .thenReturn(List.of(new RestrictionEvaluationResult(
                        productId, RestrictionDecision.ALLOW, List.of(), List.of())));

        mockMvc.perform(withGatewayAuth(post("/v1/price/restrictions:evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEvaluateBodyWithProduct(productId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].decision").value("ALLOW"))
                .andExpect(jsonPath("$.results[0].productId").value(productId.toString()));
    }

    /**
     * RRC-003: authenticated request, service returns BLOCK decision, must respond 200
     * with {@code decision == "BLOCK"}.
     *
     * Issue: #43 (ADR-0017 §200)
     */
    @Test
    @DisplayName("RRC-003: service returns BLOCK → 200 with BLOCK decision")
    void RRC003_authenticated_validRequest_serviceReturnsBlock_returns200() throws Exception {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(evaluationService.evaluate(any()))
                .thenReturn(List.of(new RestrictionEvaluationResult(
                        productId, RestrictionDecision.BLOCK, List.of(), List.of("REGION_BLOCKED"))));

        mockMvc.perform(withGatewayAuth(post("/v1/price/restrictions:evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEvaluateBodyWithProduct(productId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].decision").value("BLOCK"));
    }

    /**
     * RRC-004: BROWSE-context request where service degrades gracefully must still
     * return 200 with {@code decision == "RESTRICTION_UNKNOWN"} (no exception thrown).
     *
     * Issue: #43 (ADR-0017 §200; fail-safe policy)
     */
    @Test
    @DisplayName("RRC-004: BROWSE + service degraded → 200 with RESTRICTION_UNKNOWN")
    void RRC004_browseContext_serviceUnavailable_returns200_withUnknown() throws Exception {
        UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        when(evaluationService.evaluate(any()))
                .thenReturn(List.of(new RestrictionEvaluationResult(
                        productId, RestrictionDecision.RESTRICTION_UNKNOWN, List.of(), List.of())));

        mockMvc.perform(withGatewayAuth(post("/v1/price/restrictions:evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEvaluateBodyWithProduct(productId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].decision").value("RESTRICTION_UNKNOWN"));
    }

    /**
     * RRC-005: missing request body must produce 400 Bad Request.
     *
     * Issue: #43 (ADR-0017 §400)
     */
    @Test
    @DisplayName("RRC-005: no body → 400")
    void RRC005_missingItemsField_returns400() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/price/restrictions:evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isBadRequest());
    }

    /**
     * RRC-006: empty items list must produce 400 Bad Request ({@code @NotEmpty} on
        * {@link com.positivity.price.dto.RestrictionEvaluationRequest#items()}).
     *
     * Issue: #43 (ADR-0017 §400)
     */
    @Test
    @DisplayName("RRC-006: items=[] → 400")
    void RRC006_emptyItemsList_returns400() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/price/restrictions:evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}")))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Override endpoint: POST /v1/price/restrictions:override
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * RRC-007: authenticated user lacking {@code pricing:restriction:override} authority
     * must be rejected with 403 Forbidden.
     *
     * Issue: #43 (ADR-0017 §403)
     */
    @Test
    @DisplayName("RRC-007: authenticated without override authority → 403")
    void RRC007_missingOverrideAuthority_returns403() throws Exception {
        // defaultAuthorities() = "pricing:view" — does NOT include pricing:restriction:override
        mockMvc.perform(withGatewayAuth(post("/v1/price/restrictions:override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOverrideBody())))
                .andExpect(status().isForbidden());
    }

    /**
     * RRC-008: authenticated user with {@code pricing:restriction:override} authority and
     * a valid body must get 200 with a non-null {@code overrideId}.
     *
     * Issue: #43 (ADR-0017 §200)
     */
    @Test
    @DisplayName("RRC-008: override authority + valid body → 200 with overrideId")
    void RRC008_hasOverrideAuthority_validBody_returns200WithOverrideId() throws Exception {
        UUID overrideId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        when(overrideService.createOverride(any()))
                .thenReturn(new RestrictionOverrideResponse(
                        overrideId,
                        Instant.now().plus(24, ChronoUnit.HOURS)));

        mockMvc.perform(post("/v1/price/restrictions:override")
                        .header("X-User", "contract-test-user")
                        .header("X-Authorities", "pricing:restriction:override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOverrideBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrideId").value(overrideId.toString()))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    /**
     * RRC-009: body missing {@code reasonCode} must produce 400 Bad Request.
     *
     * Issue: #43 (ADR-0017 §400)
     */
    @Test
    @DisplayName("RRC-009: missing reasonCode → 400")
    void RRC009_missingReasonCode_returns400() throws Exception {
        String bodyWithoutReasonCode = "{\"overrideContext\":\"CHECKOUT\"}";
        mockMvc.perform(post("/v1/price/restrictions:override")
                        .header("X-User", "contract-test-user")
                        .header("X-Authorities", "pricing:restriction:override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutReasonCode))
                .andExpect(status().isBadRequest());
    }

    /**
     * RRC-010: body missing {@code overrideContext} must produce 400 Bad Request.
     *
     * Issue: #43 (ADR-0017 §400)
     */
    @Test
    @DisplayName("RRC-010: missing overrideContext → 400")
    void RRC010_missingOverrideContext_returns400() throws Exception {
        String bodyWithoutContext = "{\"reasonCode\":\"APPROVED_VENDOR\"}";
        mockMvc.perform(post("/v1/price/restrictions:override")
                        .header("X-User", "contract-test-user")
                        .header("X-Authorities", "pricing:restriction:override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutContext))
                .andExpect(status().isBadRequest());
    }

    /**
     * RRC-011: when {@link RestrictionEvaluationService#evaluate()} throws
     * {@link RestrictionServiceUnavailableException} on a commit path,
     * the controller must respond 503 Service Unavailable.
     *
     * Issue: #43 (ADR-0017 §500/503 fail-safe)
     */
    @Test
    @DisplayName("RRC-011: CHECKOUT context, service unavailable → 503")
    void RRC011_checkoutContext_serviceUnavailable_returns503() throws Exception {
        when(evaluationService.evaluate(any()))
                .thenThrow(new RestrictionServiceUnavailableException("rule data source unreachable"));

        mockMvc.perform(withGatewayAuth(post("/v1/price/restrictions:evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutEvaluateBody())))
                .andExpect(status().isServiceUnavailable());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Payload helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String validEvaluateBody() {
        return validEvaluateBodyWithProduct(UUID.fromString("00000000-0000-0000-0000-000000000099"));
    }

    private String validEvaluateBodyWithProduct(UUID productId) {
        return """
                {
                  "items": [
                    {
                      "productId": "%s",
                                                                                        "locationTag": "RETAIL_STORE",
                                                                                        "serviceTag": "WORKORDER",
                      "context": "BROWSE"
                    }
                  ]
                }
                """.formatted(productId);
    }

    private String checkoutEvaluateBody() {
        return """
                {
                  "items": [
                    {
                      "productId": "00000000-0000-0000-0000-000000000088",
                                                                                        "locationTag": "RETAIL_STORE",
                                                                                        "serviceTag": "WORKORDER",
                      "context": "CHECKOUT"
                    }
                  ]
                }
                """;
    }

    private String validOverrideBody() {
        return """
                {
                                                                        "ruleId": "00000000-0000-0000-0000-000000000011",
                                                                        "transactionId": "00000000-0000-0000-0000-000000000012",
                                                                        "productId": "00000000-0000-0000-0000-000000000013",
                  "overrideContext": "CHECKOUT",
                  "reasonCode": "APPROVED_VENDOR"
                }
                """;
    }
}
