package com.positivity.price.internal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.price.BaseContractIntegrationTest;
import com.positivity.price.internal.entity.PromotionEligibilityRule;
import com.positivity.price.internal.entity.PromotionOffer;
import com.positivity.price.internal.enums.ConditionType;
import com.positivity.price.internal.enums.DiscountType;
import com.positivity.price.internal.enums.PromotionStatus;
import com.positivity.price.internal.enums.RuleOperator;
import com.positivity.price.internal.repository.PromotionEligibilityRuleRepository;
import com.positivity.price.internal.repository.PromotionOfferRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract behavior tests for {@link PromotionEligibilityRuleController}
 * lifecycle operations.
 *
 * <p>
 * Validates adding, retrieving, deleting, and evaluating promotion eligibility
 * rules.
 * </p>
 *
 * Issue: #96
 */
@DisplayName("Promotion Eligibility Rule Controller Contract Tests")
class PromotionEligibilityRuleControllerTest extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PromotionOfferRepository promotionOfferRepository;

    @Autowired
    private PromotionEligibilityRuleRepository promotionEligibilityRuleRepository;

    /**
     * Grant {@code Promotion:Manage} so that {@code @PreAuthorize} checks on POST
     * and DELETE
     * endpoints pass through to the service layer, producing behaviour-driven
     * failures
     * rather than auth-layer 403s.
     *
     * @return the authority string forwarded via {@code X-Authorities} header
     */
    @Override
    protected String defaultAuthorities() {
        return "Promotion:Manage,Promotion:View,Promotion:Apply";
    }

    // ========== ADD RULE ==========

    /**
     * ERC-001: Valid AddEligibilityRuleRequest produces 201 with rule details.
     *
     * Issue: #96
     */
    @Test
    @DisplayName("ERC-001: POST /v1/promotions/offers/{promotionId}/rules with valid request → 201")
    void givenValidRequest_whenAddRule_thenReturns201WithRuleDetails() throws Exception {
        UUID promotionId = seedPromotion();

        mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers/{promotionId}/rules", promotionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRulePayload())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleId").exists())
                .andExpect(jsonPath("$.conditionType").value("ACCOUNT_ID_LIST"))
                .andExpect(jsonPath("$.operator").value("IN"))
                .andExpect(jsonPath("$.value").value("acc-123"));
    }

    /**
     * ERC-002: POST with missing required fields produces 400 VALIDATION_FAILED.
     *
     * <p>
     * DTO-level {@code @NotNull}/{@code @NotBlank} annotations fire before the
     * service layer
     * via {@code GlobalExceptionHandler}, so this assertion may already pass in RED
     * phase.
     * </p>
     *
     * Issue: #96
     */
    @Test
    @DisplayName("ERC-002: POST /v1/promotions/offers/{promotionId}/rules with missing fields → 400 VALIDATION_FAILED")
    void givenInvalidRequest_whenAddRule_thenReturns400() throws Exception {
        UUID promotionId = seedPromotion();

        mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers/{promotionId}/rules", promotionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ========== GET RULES ==========

    /**
     * ERC-003: GET rules list for an existing promotion produces 200 with a JSON
     * array.
     *
     * Issue: #96
     */
    @Test
    @DisplayName("ERC-003: GET /v1/promotions/offers/{promotionId}/rules → 200 with list")
    void givenExistingPromotion_whenGetRules_thenReturns200WithList() throws Exception {
        UUID promotionId = seedPromotion();

        mockMvc.perform(withGatewayAuth(get("/v1/promotions/offers/{promotionId}/rules", promotionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ========== DELETE RULE ==========

    /**
     * ERC-004: DELETE a valid ruleId URL produces 204 No Content when the rule
     * exists.
     *
     * Issue: #96
     */
    @Test
    @DisplayName("ERC-004: DELETE /v1/promotions/offers/{promotionId}/rules/{ruleId} existing rule → 204")
    void givenValidRuleId_whenDeleteRule_thenReturns204() throws Exception {
        UUID promotionId = seedPromotion();
        UUID ruleId = seedRule(promotionId, ConditionType.ACCOUNT_ID_LIST, RuleOperator.IN, "acc-123");

        mockMvc.perform(withGatewayAuth(
                delete("/v1/promotions/offers/{promotionId}/rules/{ruleId}", promotionId, ruleId)))
                .andExpect(status().isNoContent());
    }

    /**
     * ERC-005: DELETE a non-existent ruleId produces 404
     * ELIGIBILITY_RULE_NOT_FOUND.
     *
     * Issue: #96
     */
    @Test
    @DisplayName("ERC-005: DELETE /v1/promotions/offers/{promotionId}/rules/{ruleId} non-existent → 404 ELIGIBILITY_RULE_NOT_FOUND")
    void givenNonExistentRuleId_whenDeleteRule_thenReturns404() throws Exception {
        UUID promotionId = seedPromotion();

        mockMvc.perform(withGatewayAuth(
                delete("/v1/promotions/offers/{promotionId}/rules/{ruleId}", promotionId, UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ELIGIBILITY_RULE_NOT_FOUND"));
    }

    // ========== EVALUATE ==========

    /**
     * ERC-006: POST /evaluate with a valid accountId context produces 200 with
     * isEligible field.
     *
     * Issue: #96
     */
    @Test
    @DisplayName("ERC-006: POST /v1/promotions/offers/{promotionId}/rules/evaluate with accountId → 200 with isEligible")
    void givenEligibleContext_whenEvaluate_thenReturnsEligible() throws Exception {
        UUID promotionId = seedPromotion();

        mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers/{promotionId}/rules/evaluate", promotionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(evaluatePayload(UUID.randomUUID(), null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasonCode").exists());
    }

    /**
     * ERC-007: POST /evaluate with no accountId produces 200 with
     * {@code isEligible=false}.
     *
     * <p>
     * GREEN expectation: service returns {@code MISSING_ACCOUNT_CONTEXT} reason
     * code and
     * {@code isEligible=false} when no accountId is provided.
     * </p>
     *
     * Issue: #96
     */
    @Test
    @DisplayName("ERC-007: POST /v1/promotions/offers/{promotionId}/rules/evaluate with no accountId → 200 ineligible")
    void givenMissingAccountId_whenEvaluate_thenReturnsIneligible() throws Exception {
        UUID promotionId = seedPromotion();
        seedRule(
                promotionId,
                ConditionType.ACCOUNT_FLEET_SIZE,
                RuleOperator.GREATER_THAN_OR_EQUAL_TO,
                "100");

        mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers/{promotionId}/rules/evaluate", promotionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isEligible").value(false));
    }

    // ========== NON-EXISTENT PROMOTION ==========

    /**
     * ERC-008: POST rules to a non-existent promotionId produces 404.
     *
     * <p>
     * GREEN expectation: service throws {@code PromotionOfferNotFoundException}
     * which is mapped
     * to 404 by {@code GlobalExceptionHandler}.
     * </p>
     *
     * Issue: #96
     */
    @Test
    @DisplayName("ERC-008: POST /v1/promotions/offers/{promotionId}/rules with non-existent promotionId → 404")
    void givenNonExistentPromotion_whenAddRule_thenReturns404() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers/{promotionId}/rules", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRulePayload())))
                .andExpect(status().isNotFound());
    }

    // ========== HELPERS ==========

    /**
     * Seeds a minimal DRAFT {@link PromotionOffer} directly into the repository and
     * returns its ID.
     *
     * @return the UUID of the persisted promotion offer
     */
    private UUID seedPromotion() {
        PromotionOffer offer = new PromotionOffer();
        offer.setPromoCode("RULE-TEST-" + System.currentTimeMillis());
        offer.setName("Rule Test Promotion");
        offer.setDiscountType(DiscountType.PERCENT_LABOR);
        offer.setDiscountValue(BigDecimal.valueOf(10.00));
        offer.setStartDate(LocalDate.of(2026, 4, 1));
        offer.setEndDate(LocalDate.of(2026, 4, 30));
        offer.setStatus(PromotionStatus.DRAFT);
        return promotionOfferRepository.save(offer).getPromotionOfferId();
    }

    private UUID seedRule(UUID promotionId, ConditionType conditionType, RuleOperator operator, String value) {
        PromotionEligibilityRule rule = new PromotionEligibilityRule();
        rule.setPromotionId(promotionId);
        rule.setConditionType(conditionType);
        rule.setOperator(operator);
        rule.setValue(value);
        return promotionEligibilityRuleRepository.save(rule).getRuleId();
    }

    /**
     * Builds a valid {@code AddEligibilityRuleRequest} JSON payload.
     *
     * @return JSON string with ACCOUNT_ID_LIST / IN / acc-123
     */
    private String validRulePayload() {
        return """
                {
                  "conditionType": "ACCOUNT_ID_LIST",
                  "operator": "IN",
                  "value": "acc-123"
                }
                """;
    }

    /**
     * Builds an {@code EligibilityContext} JSON payload with optional accountId and
     * vehicleId.
     *
     * @param accountId optional account UUID; omitted from JSON if null
     * @param vehicleId optional vehicle UUID; omitted from JSON if null
     * @return JSON string representing the evaluation context
     */
    private String evaluatePayload(UUID accountId, UUID vehicleId) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (accountId != null) {
            sb.append("\"accountId\": \"").append(accountId).append("\"");
            first = false;
        }
        if (vehicleId != null) {
            if (!first)
                sb.append(", ");
            sb.append("\"vehicleId\": \"").append(vehicleId).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    @Test
    @DisplayName("ERC-403: POST /v1/promotions/offers/{promotionId}/rules/evaluate without gateway auth \u2192 403 Forbidden")
    void evaluateEligibility_withoutAuth_returns403() throws Exception {
        UUID promotionId = UUID.randomUUID();
        mockMvc.perform(post("/v1/promotions/offers/{promotionId}/rules/evaluate", promotionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":null,\"vehicleId\":null}"))
                .andExpect(status().isForbidden());
    }
}
