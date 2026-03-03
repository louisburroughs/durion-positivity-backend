package com.positivity.price.internal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.price.BaseContractIntegrationTest;
import com.positivity.price.internal.entity.PromotionOffer;
import com.positivity.price.internal.enums.DiscountType;
import com.positivity.price.internal.enums.PromotionStatus;
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
 * Contract behavior tests for {@link PromotionOfferController} lifecycle
 * operations.
 *
 * <p>
 * Validates creation, retrieval, activation, and deactivation of promotion
 * offers.
 * Tests are intentionally RED in the scaffold
 * phase—{@code PromotionOfferServiceImpl}
 * throws {@code UnsupportedOperationException} for all methods until GREEN
 * implementation.
 * </p>
 *
 * Issue: #97
 */
@DisplayName("Promotion Offer Controller Contract Tests")
class PromotionOfferControllerTest extends BaseContractIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private PromotionOfferRepository promotionOfferRepository;

        /**
         * Grant {@code Promotion:Manage} so that {@code @PreAuthorize} checks on POST
         * and PATCH
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

        // ========== CREATE ==========

        /**
         * AC-1: Valid request produces 201 with DRAFT status and matching promoCode.
         *
         * Issue: #97
         */
        @Test
        @DisplayName("POC-001: POST /v1/promotions/offers with valid request → 201 DRAFT")
        void givenValidRequest_whenCreateOffer_thenReturns201WithDraftStatus() throws Exception {
                mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validOfferPayload("SPRING25"))))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status").value("DRAFT"))
                                .andExpect(jsonPath("$.promoCode").value("SPRING25"))
                                .andExpect(jsonPath("$.promotionOfferId").exists());
        }

        /**
         * AC-2: Duplicate promoCode produces 409 DUPLICATE_PROMO_CODE.
         *
         * Issue: #97
         */
        @Test
        @DisplayName("POC-002: POST /v1/promotions/offers with duplicate promoCode → 409 DUPLICATE_PROMO_CODE")
        void givenDuplicatePromoCode_whenCreateOffer_thenReturns409() throws Exception {
                // First creation must succeed; second with same code must conflict.
                mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validOfferPayload("DUP-CODE"))))
                                .andExpect(status().isCreated());

                mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validOfferPayload("DUP-CODE"))))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("DUPLICATE_PROMO_CODE"));
        }

        /**
         * AC-3: startDate after endDate produces 422 PROMOTION_OFFER_INVALID_STATE.
         *
         * Issue: #97
         */
        @Test
        @DisplayName("POC-003: POST /v1/promotions/offers with startDate after endDate → 422 PROMOTION_OFFER_INVALID_STATE")
        void givenStartDateAfterEndDate_whenCreateOffer_thenReturns422() throws Exception {
                mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(offerPayloadWithDates("BADDATES", "2026-12-31", "2026-01-01"))))
                                .andExpect(status().is(422))
                                .andExpect(jsonPath("$.code").value("PROMOTION_OFFER_INVALID_STATE"));
        }

        /**
         * AC-4: Missing required fields produce 400 VALIDATION_FAILED.
         *
         * <p>
         * DTO-level {@code @NotBlank}/{@code @NotNull} annotations fire before the
         * service,
         * so this assertion may already pass in RED phase via
         * {@code GlobalExceptionHandler}.
         * </p>
         *
         * Issue: #97
         */
        @Test
        @DisplayName("POC-004: POST /v1/promotions/offers with missing required fields → 400 VALIDATION_FAILED")
        void givenMissingRequiredFields_whenCreateOffer_thenReturns400() throws Exception {
                mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        // ========== RETRIEVAL BY ID ==========

        /**
         * AC-5: GET by existing id produces 200 with promoCode.
         *
         * <p>
         * GREEN note: seed a PromotionOffer via the create endpoint first, then
         * retrieve its id.
         * </p>
         *
         * Issue: #97
         */
        @Test
        @DisplayName("POC-005: GET /v1/promotions/offers/{id} existing → 200 with promoCode")
        void givenExistingId_whenGetOfferById_thenReturns200WithPromoCode() throws Exception {
                // Seed offer via create, then retrieve by returned id.
                var createResult = mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validOfferPayload("GET-BY-ID-" + uniqueSuffix()))))
                                .andExpect(status().isCreated())
                                .andReturn();

                String offerId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                                .readTree(createResult.getResponse().getContentAsString())
                                .get("promotionOfferId")
                                .asText();

                mockMvc.perform(withGatewayAuth(get("/v1/promotions/offers/{id}", UUID.fromString(offerId))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.promoCode").exists())
                                .andExpect(jsonPath("$.promotionOfferId").value(offerId));
        }

        /**
         * AC-6: GET by non-existent id produces 404 PROMOTION_OFFER_NOT_FOUND.
         *
         * Issue: #97
         */
        @Test
        @DisplayName("POC-006: GET /v1/promotions/offers/{id} not-found → 404 PROMOTION_OFFER_NOT_FOUND")
        void givenNonExistentId_whenGetOfferById_thenReturns404() throws Exception {
                mockMvc.perform(withGatewayAuth(get("/v1/promotions/offers/{id}", UUID.randomUUID())))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.code").value("PROMOTION_OFFER_NOT_FOUND"));
        }

        // ========== RETRIEVAL BY CODE ==========

        /**
         * AC-7: GET by existing promoCode produces 200.
         *
         * <p>
         * GREEN note: seed offer first, then retrieve by promoCode.
         * </p>
         *
         * Issue: #97
         */
        @Test
        @DisplayName("POC-007: GET /v1/promotions/offers/by-code/{promoCode} existing → 200")
        void givenExistingCode_whenGetOfferByCode_thenReturns200() throws Exception {
                String promoCode = "BYCODE-" + uniqueSuffix();

                mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validOfferPayload(promoCode))))
                                .andExpect(status().isCreated());

                mockMvc.perform(withGatewayAuth(get("/v1/promotions/offers/by-code/{promoCode}", promoCode)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.promoCode").value(promoCode));
        }

        /**
         * AC-8: GET by non-existent promoCode produces 404 PROMOTION_OFFER_NOT_FOUND.
         *
         * Issue: #97
         */
        @Test
        @DisplayName("POC-008: GET /v1/promotions/offers/by-code/{promoCode} not-found → 404")
        void givenNonExistentCode_whenGetOfferByCode_thenReturns404() throws Exception {
                mockMvc.perform(withGatewayAuth(get("/v1/promotions/offers/by-code/{promoCode}", "NO-SUCH-CODE")))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.code").value("PROMOTION_OFFER_NOT_FOUND"));
        }

        // ========== STATE TRANSITIONS ==========

        /**
         * AC-9: Activate a DRAFT offer → 200 ACTIVE.
         *
         * <p>
         * GREEN note: seed a DRAFT offer, then PATCH activate it.
         * </p>
         *
         * Issue: #97
         */
        @Test
        @DisplayName("POC-009: PATCH /v1/promotions/offers/{id}/activate on DRAFT offer → 200 ACTIVE")
        void givenDraftOffer_whenActivate_thenReturns200WithActiveStatus() throws Exception {
                String promoCode = "ACTIVATE-" + uniqueSuffix();

                var createResult = mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validOfferPayload(promoCode))))
                                .andExpect(status().isCreated())
                                .andReturn();

                String offerId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                                .readTree(createResult.getResponse().getContentAsString())
                                .get("promotionOfferId")
                                .asText();

                mockMvc.perform(withGatewayAuth(patch("/v1/promotions/offers/{id}/activate", UUID.fromString(offerId))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        /**
         * AC-9b: Activate an INACTIVE offer → 200 ACTIVE.
         *
         * Issue: #97
         */
        @Test
        @DisplayName("POC-012: PATCH /v1/promotions/offers/{id}/activate on INACTIVE offer → 200 ACTIVE")
        void givenInactiveOffer_whenActivate_thenReturns200WithActiveStatus() throws Exception {
                PromotionOffer inactiveOffer = new PromotionOffer();
                inactiveOffer.setPromoCode("INACTIVE-" + uniqueSuffix());
                inactiveOffer.setName("Inactive Promotion");
                inactiveOffer.setDiscountType(DiscountType.PERCENT_LABOR);
                inactiveOffer.setDiscountValue(BigDecimal.valueOf(7.50));
                inactiveOffer.setStartDate(LocalDate.of(2026, 5, 1));
                inactiveOffer.setEndDate(LocalDate.of(2026, 5, 31));
                inactiveOffer.setStatus(PromotionStatus.INACTIVE);

                PromotionOffer savedInactiveOffer = promotionOfferRepository.save(inactiveOffer);
                UUID inactiveOfferId = savedInactiveOffer.getPromotionOfferId();

                mockMvc.perform(withGatewayAuth(patch("/v1/promotions/offers/{id}/activate", inactiveOfferId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        /**
         * AC-10: Deactivate an ACTIVE offer → 200 INACTIVE.
         *
         * <p>
         * GREEN note: seed a DRAFT offer, activate it, then deactivate.
         * </p>
         *
         * Issue: #97
         */
        @Test
        @DisplayName("POC-010: PATCH /v1/promotions/offers/{id}/deactivate on ACTIVE offer → 200 INACTIVE")
        void givenActiveOffer_whenDeactivate_thenReturns200WithInactiveStatus() throws Exception {
                String promoCode = "DEACTIVATE-" + uniqueSuffix();

                var createResult = mockMvc.perform(withGatewayAuth(post("/v1/promotions/offers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validOfferPayload(promoCode))))
                                .andExpect(status().isCreated())
                                .andReturn();

                String offerId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                                .readTree(createResult.getResponse().getContentAsString())
                                .get("promotionOfferId")
                                .asText();

                mockMvc.perform(withGatewayAuth(patch("/v1/promotions/offers/{id}/activate", UUID.fromString(offerId))))
                                .andExpect(status().isOk());

                mockMvc.perform(withGatewayAuth(
                                patch("/v1/promotions/offers/{id}/deactivate", UUID.fromString(offerId))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("INACTIVE"));
        }

        /**
         * AC-11: Activate an EXPIRED offer → 422 PROMOTION_OFFER_INVALID_STATE.
         *
         * <p>
         * GREEN note: requires a promotion offer seeded directly with EXPIRED status.
         * Cannot create an EXPIRED offer via the public API; seed via repository in
         * GREEN phase.
         * </p>
         *
         * Issue: #97
         */
        @Test
        @DisplayName("POC-011: PATCH /v1/promotions/offers/{id}/activate on EXPIRED offer → 422 PROMOTION_OFFER_INVALID_STATE")
        void givenExpiredOffer_whenActivate_thenReturns422InvalidState() throws Exception {
                PromotionOffer expiredOffer = new PromotionOffer();
                expiredOffer.setPromoCode("EXPIRED-" + uniqueSuffix());
                expiredOffer.setName("Expired Promotion");
                expiredOffer.setDiscountType(DiscountType.PERCENT_LABOR);
                expiredOffer.setDiscountValue(BigDecimal.valueOf(5.00));
                expiredOffer.setStartDate(LocalDate.of(2026, 1, 1));
                expiredOffer.setEndDate(LocalDate.of(2026, 1, 31));
                expiredOffer.setStatus(PromotionStatus.EXPIRED);

                PromotionOffer savedExpiredOffer = promotionOfferRepository.save(expiredOffer);
                UUID expiredOfferId = savedExpiredOffer.getPromotionOfferId();

                mockMvc.perform(withGatewayAuth(patch("/v1/promotions/offers/{id}/activate", expiredOfferId)))
                                .andExpect(status().is(422))
                                .andExpect(jsonPath("$.code").value("PROMOTION_OFFER_INVALID_STATE"));
        }

        // ========== HELPERS ==========

        private String validOfferPayload(String promoCode) {
                return String.format("""
                                {
                                  "promoCode": "%s",
                                  "name": "Spring Sale 2026",
                                  "discountType": "PERCENT_LABOR",
                                  "discountValue": 10.00,
                                  "startDate": "2026-04-01",
                                  "endDate": "2026-04-30"
                                }
                                """, promoCode);
        }

        private String offerPayloadWithDates(String promoCode, String startDate, String endDate) {
                return String.format("""
                                {
                                  "promoCode": "%s",
                                  "name": "Date Invariant Test",
                                  "discountType": "FIXED_INVOICE",
                                  "discountValue": 5.00,
                                  "startDate": "%s",
                                  "endDate": "%s"
                                }
                                """, promoCode, startDate, endDate);
        }

        /**
         * Produces a short unique suffix for promo codes to avoid test pollution.
         *
         * @return suffix string based on current time millis
         */
        private String uniqueSuffix() {
                return String.valueOf(System.currentTimeMillis()).substring(8);
        }

        @Test
        @DisplayName("POC-403A: GET /v1/promotions/offers/{id} without gateway auth \u2192 403 Forbidden")
        void getOfferById_withoutAuth_returns403() throws Exception {
                UUID id = UUID.randomUUID();
                mockMvc.perform(get("/v1/promotions/offers/{id}", id))
                                .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POC-403B: GET /v1/promotions/offers/by-code/{code} without gateway auth \u2192 403 Forbidden")
        void getOfferByCode_withoutAuth_returns403() throws Exception {
                mockMvc.perform(get("/v1/promotions/offers/by-code/SUMMER10"))
                                .andExpect(status().isForbidden());
        }
}
