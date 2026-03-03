package com.positivity.price.internal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.price.BaseContractIntegrationTest;
import com.positivity.price.internal.entity.PromotionEligibilityRule;
import com.positivity.price.internal.entity.PromotionOffer;
import com.positivity.price.internal.enums.ConditionType;
import com.positivity.price.internal.enums.DiscountType;
import com.positivity.price.internal.enums.PromotionStatus;
import com.positivity.price.internal.enums.RuleCombination;
import com.positivity.price.internal.enums.RuleOperator;
import com.positivity.price.internal.repository.PromotionEligibilityRuleRepository;
import com.positivity.price.internal.repository.PromotionOfferRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the apply-promotion endpoint (POST
 * /v1/promotions/offers/apply).
 *
 * <p>
 * Covers the full behavioral contract of
 * {@code PromotionOfferServiceImpl.applyPromotion}:
 * success with PERCENT_LABOR and FIXED_INVOICE discount types, PROMO_NOT_FOUND
 * for an
 * unknown promo code, and PROMO_NOT_APPLICABLE for inactive and date-expired
 * promotions.
 * No eligibility rules are seeded; the service treats an empty rule set as
 * unconditionally
 * eligible.
 * </p>
 *
 * Issue: #95
 */
@DisplayName("Apply Promotion Integration Tests")
class ApplyPromotionIntegrationTest extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PromotionOfferRepository promotionOfferRepository;

    @Autowired
    private PromotionEligibilityRuleRepository ruleRepository;

    /**
     * Override to supply the {@code Promotion:Apply} authority required by the
     * apply endpoint.
     *
     * @return the authority string forwarded via {@code X-Authorities} header
     */
    @Override
    protected String defaultAuthorities() {
        return "Promotion:Apply";
    }

    @BeforeEach
    void cleanup() {
        ruleRepository.deleteAll();
        promotionOfferRepository.deleteAll();
    }

    // ========== APT-001 ==========

    /**
     * Happy path: ACTIVE PERCENT_LABOR 20% promotion applied against a $500
     * estimate.
     * Adjustment must be PROMOTION type, amount must be negative, and total must be
     * less than the submitted subtotal.
     *
     * Issue: #95
     */
    @Test
    @DisplayName("APT-001: ACTIVE PERCENT_LABOR promotion → 200 with negative adjustment and reduced total")
    void givenActivePromotion_whenApply_thenReturns200WithAdjustment() throws Exception {
        seedActiveOffer("SUMMER20", DiscountType.PERCENT_LABOR, BigDecimal.valueOf(20),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30));

        mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content(applyRequest("SUMMER20", "500.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedAdjustments[0].type").value("PROMOTION"))
                .andExpect(jsonPath("$.appliedAdjustments[0].amount").value(-100.00))
                .andExpect(jsonPath("$.subtotal").value(500.00))
                .andExpect(jsonPath("$.total").value(400.00));
    }

    // ========== APT-002 ==========

    /**
     * Unknown promo code → 400 with error code PROMO_NOT_FOUND.
     *
     * Issue: #95
     */
    @Test
    @DisplayName("APT-002: Non-existent promoCode → 400 PROMO_NOT_FOUND")
    void givenNonExistentPromoCode_whenApply_thenReturns400WithPromoNotFound() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content(applyRequest("DOESNOTEXIST", "500.00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROMO_NOT_FOUND"));
    }

    // ========== APT-003 ==========

    /**
     * INACTIVE promotion → 400 with error code PROMO_NOT_APPLICABLE.
     * Status check precedes date-window and eligibility checks.
     *
     * Issue: #95
     */
    @Test
    @DisplayName("APT-003: INACTIVE promotion → 400 PROMO_NOT_APPLICABLE")
    void givenInactivePromotion_whenApply_thenReturns400WithNotApplicable() throws Exception {
        PromotionOffer offer = new PromotionOffer();
        offer.setPromoCode("INACTIVE01");
        offer.setName("Inactive Promo");
        offer.setDiscountType(DiscountType.PERCENT_LABOR);
        offer.setDiscountValue(BigDecimal.valueOf(10));
        offer.setStartDate(LocalDate.now().minusDays(10));
        offer.setEndDate(LocalDate.now().plusDays(10));
        offer.setStatus(PromotionStatus.INACTIVE);
        promotionOfferRepository.save(offer);

        mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content(applyRequest("INACTIVE01", "500.00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROMO_NOT_APPLICABLE"));
    }

    // ========== APT-004 ==========

    /**
     * ACTIVE promotion whose {@code endDate} is in the past → 400
     * PROMO_NOT_APPLICABLE.
     * Confirms the date-window guard fires even when status is ACTIVE.
     *
     * Issue: #95
     */
    @Test
    @DisplayName("APT-004: Expired date window → 400 PROMO_NOT_APPLICABLE")
    void givenExpiredDateWindow_whenApply_thenReturns400WithNotApplicable() throws Exception {
        seedActiveOffer("EXPIRED99", DiscountType.PERCENT_LABOR, BigDecimal.valueOf(10),
                LocalDate.now().minusDays(30), LocalDate.now().minusDays(1));

        mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content(applyRequest("EXPIRED99", "500.00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROMO_NOT_APPLICABLE"));
    }

    // ========== APT-005 ==========

    /**
     * ACTIVE FIXED_INVOICE promotion with discountValue=50: total must equal
     * subtotal − 50.
     * Verifies the flat-amount discount branch in the service.
     *
     * @see com.positivity.price.internal.service.PromotionOfferServiceImpl#applyPromotion
     *
     *      Issue: #95
     */
    @Test
    @DisplayName("APT-005: FIXED_INVOICE promotion → 200 with correct flat discount applied")
    void givenFixedInvoicePromo_whenApply_thenDiscountIsFixed() throws Exception {
        seedActiveOffer("FIXED50", DiscountType.FIXED_INVOICE, BigDecimal.valueOf(50),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30));

        mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content(applyRequest("FIXED50", "500.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(450.00));
    }

    // ========== APT-006 ==========

    /**
     * ACTIVE promotion with an ACCOUNT_FLEET_SIZE >= 1000 rule: the stub account
     * data provider returns no fleet context, so evaluation returns
     * MISSING_ACCOUNT_CONTEXT
     * (ineligible) → 400 PROMO_NOT_APPLICABLE.
     *
     * Issue: #95
     */
    @Test
    @DisplayName("APT-006: Eligibility rule rejects account → 400 PROMO_NOT_APPLICABLE")
    void givenEligibilityRuleRejectsAccount_whenApply_thenReturns400NotApplicable() throws Exception {
        PromotionOffer offer = seedActiveOffer("FLEETONLY", DiscountType.PERCENT_LABOR,
                BigDecimal.valueOf(10), LocalDate.now().minusDays(30), LocalDate.now().plusDays(30));

        PromotionEligibilityRule rule = new PromotionEligibilityRule();
        rule.setPromotionId(offer.getPromotionOfferId());
        rule.setConditionType(ConditionType.ACCOUNT_FLEET_SIZE);
        rule.setOperator(RuleOperator.GREATER_THAN_OR_EQUAL_TO);
        rule.setValue("1000");
        rule.setRuleCombination(RuleCombination.AND);
        ruleRepository.save(rule);

        mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content(applyRequest("FLEETONLY", "500.00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROMO_NOT_APPLICABLE"));
    }

    // ========== APT-007 ==========

    /**
     * Authenticated user missing {@code Promotion:Apply} authority → 403 Forbidden.
     * Confirms the {@code @PreAuthorize} guard fires for the apply endpoint.
     *
     * Issue: #95
     */
    @Test
    @DisplayName("APT-007: Missing Promotion:Apply authority → 403 Forbidden")
    void givenNoPromotionApplyAuthority_whenApply_thenReturns403() throws Exception {
        mockMvc.perform(post("/v1/promotions/offers/apply")
                .header("X-User", "test-user")
                .header("X-Authorities", "Promotion:Manage")
                .contentType(MediaType.APPLICATION_JSON)
                .content(applyRequest("ANY", "100.00")))
                .andExpect(status().isForbidden());
    }

    // ========== HELPERS ==========

    /**
     * Seeds an ACTIVE {@link PromotionOffer} directly via repository to bypass POST
     * validation.
     *
     * @param promoCode     the promotion code
     * @param discountType  the discount strategy
     * @param discountValue the discount magnitude
     * @param startDate     inclusive validity start
     * @param endDate       inclusive validity end
     * @return the persisted entity
     */
    private PromotionOffer seedActiveOffer(
            String promoCode,
            DiscountType discountType,
            BigDecimal discountValue,
            LocalDate startDate,
            LocalDate endDate) {
        PromotionOffer offer = new PromotionOffer();
        offer.setPromoCode(promoCode);
        offer.setName("Test Promotion");
        offer.setDiscountType(discountType);
        offer.setDiscountValue(discountValue);
        offer.setStartDate(startDate);
        offer.setEndDate(endDate);
        offer.setStatus(PromotionStatus.ACTIVE);
        return promotionOfferRepository.save(offer);
    }

    /**
     * Produces a valid {@code ApplyPromotionRequest} JSON body.
     *
     * @param promotionCode the promotion code to apply
     * @param subtotal      the estimate subtotal as a numeric string
     * @return JSON string suitable for the request body
     */
    private String applyRequest(String promotionCode, String subtotal) {
        return String.format("""
                {
                  "promotionCode": "%s",
                  "estimateContext": {
                    "estimateId": "%s",
                    "customerId": "%s",
                    "lineItems": [{"sku": "P001", "quantity": 1, "unitPrice": 100.00}],
                    "subtotal": %s
                  }
                }
                """, promotionCode, UUID.randomUUID(), UUID.randomUUID(), subtotal);
    }
}
