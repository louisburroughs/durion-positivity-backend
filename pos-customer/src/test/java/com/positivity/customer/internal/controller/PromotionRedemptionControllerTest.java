package com.positivity.customer.internal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.customer.BaseContractIntegrationTest;
import com.positivity.customer.config.TestSecurityConfig;
import com.positivity.customer.internal.dto.RecordRedemptionRequest;
import com.positivity.customer.internal.entity.PromotionRedemption;
import com.positivity.customer.internal.repository.PromotionRedemptionRepository;

/**
 * Integration tests for POST /v1/promotions/redemptions and GET /v1/promotions/redemptions/by-customer/{customerId}.
 */
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Transactional
@DisplayName("Promotion Redemption Controller Contract Tests")
class PromotionRedemptionControllerTest extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PromotionRedemptionRepository promotionRedemptionRepository;

    /**
     * Grant {@code Promotion:RecordRedemption} so POST requests pass
     * {@code @PreAuthorize} on {@link PromotionRedemptionController} and reach the
     * service layer, producing behaviour-driven failures rather than auth 403s.
     *
     * @return the authority string forwarded via {@code X-Authorities} header
     */
    @Override
    protected String defaultAuthorities() {
                return "Promotion:RecordRedemption,Promotion:ViewRedemption";
    }

    // -------------------------------------------------------------------------
    // PRC-001 — Valid request → 201 with populated response body
    // -------------------------------------------------------------------------

    /**
     * PRC-001: {@code POST /v1/promotions/redemptions} with a valid {@link RecordRedemptionRequest}
     * returns 201 with {@code promotionId}, {@code customerId}, {@code workorderId},
     * and {@code recordedOverLimit=false} present in the response body.
     *
     * Issue: #94
     */
    @Test
    @DisplayName("PRC-001: POST valid redemption request → 201 with response body fields")
    void givenValidRequest_whenRecordRedemption_thenReturns201() throws Exception {
        UUID promotionId = UUID.randomUUID();
        UUID customerId  = UUID.randomUUID();
        UUID workorderId = UUID.randomUUID();

        mockMvc.perform(withGatewayAuth(post("/v1/promotions/redemptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRedemptionPayload(promotionId, customerId, workorderId, false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.promotionId").value(promotionId.toString()))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.workorderId").value(workorderId.toString()))
                .andExpect(jsonPath("$.recordedOverLimit").value(false));
    }

    // -------------------------------------------------------------------------
    // PRC-002 — Duplicate promotionId + workorderId → 409 DUPLICATE_REDEMPTION
    // -------------------------------------------------------------------------

    /**
     * PRC-002: Posting the same {@code (promotionId, workorderId)} pair when a
     * matching record already exists returns 409 with error code
     * {@code "DUPLICATE_REDEMPTION"}.
     *
     * Issue: #94
     */
    @Test
    @DisplayName("PRC-002: POST same promotionId+workorderId when record exists → 409 DUPLICATE_REDEMPTION")
    void givenDuplicateRedemption_whenRecordAgain_thenReturns409() throws Exception {
        UUID promotionId = UUID.randomUUID();
        UUID customerId  = UUID.randomUUID();
        UUID workorderId = UUID.randomUUID();

        // Seed an existing redemption directly through the repository
        promotionRedemptionRepository.saveAndFlush(
                buildRedemptionEntity(promotionId, customerId, workorderId, false));

        mockMvc.perform(withGatewayAuth(post("/v1/promotions/redemptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRedemptionPayload(promotionId, customerId, workorderId, false))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REDEMPTION"));
    }

    // -------------------------------------------------------------------------
    // PRC-003 — Missing required field → 400 (Bean Validation)
    // -------------------------------------------------------------------------

    /**
     * PRC-003: {@code POST /v1/promotions/redemptions} with the required
     * {@code promotionId} field absent returns 400, enforced by the
     * {@code @NotNull} constraint on {@link RecordRedemptionRequest#getPromotionId()}.
     *
     * Issue: #94
     */
    @Test
    @DisplayName("PRC-003: POST missing promotionId → 400 validation error")
    void givenMissingRequiredField_whenRecordRedemption_thenReturns400() throws Exception {
        String missingPromoIdPayload = """
                {
                  "customerId": "%s",
                  "workorderId": "%s",
                  "discountAmount": "10.00",
                  "discountType": "PERCENTAGE",
                  "promotionCode": "SPRING25"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(withGatewayAuth(post("/v1/promotions/redemptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingPromoIdPayload)))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // PRC-004 — GET by customerId returns non-empty list
    // -------------------------------------------------------------------------

    /**
     * PRC-004: {@code GET /v1/promotions/redemptions/by-customer/{customerId}}
     * returns 200 with a non-empty list for a customer who has at least one
     * existing redemption record.
     *
     * Issue: #94
     */
    @Test
    @DisplayName("PRC-004: GET by customerId with existing redemption → 200 non-empty list")
    void givenCustomerWithRedemptions_whenGetByCustomer_thenReturns200WithList() throws Exception {
        UUID customerId  = UUID.randomUUID();
        UUID promotionId = UUID.randomUUID();
        UUID workorderId = UUID.randomUUID();

        promotionRedemptionRepository.saveAndFlush(
                buildRedemptionEntity(promotionId, customerId, workorderId, false));

        mockMvc.perform(withGatewayAuth(get("/v1/promotions/redemptions/by-customer/{customerId}", customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].promotionId").exists());
    }

    // -------------------------------------------------------------------------
    // PRC-005 — recordedOverLimit flag is propagated (stretch)
    // -------------------------------------------------------------------------

    /**
     * PRC-005 (Stretch): {@code POST /v1/promotions/redemptions} with
     * {@code recordedOverLimit=true} returns 201 and the response body reflects
     * {@code recordedOverLimit=true}.
     *
     * Issue: #94
     */
    @Test
    @DisplayName("PRC-005: POST with recordedOverLimit=true → 201 with flag propagated")
    void givenRecordedOverLimit_whenRecordRedemption_thenReturns201WithFlag() throws Exception {
        UUID promotionId = UUID.randomUUID();
        UUID customerId  = UUID.randomUUID();
        UUID workorderId = UUID.randomUUID();

        mockMvc.perform(withGatewayAuth(post("/v1/promotions/redemptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRedemptionPayload(promotionId, customerId, workorderId, true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recordedOverLimit").value(true));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a valid JSON payload for {@code POST /v1/promotions/redemptions}.
     *
     * @param promotionId       the promotion UUID to embed
     * @param customerId        the customer UUID to embed
     * @param workorderId       the workorder UUID to embed
     * @param recordedOverLimit whether to set the over-limit flag
     * @return JSON string ready for use as the request body
     */
    private String validRedemptionPayload(UUID promotionId, UUID customerId, UUID workorderId,
            boolean recordedOverLimit) {
        return """
                {
                  "promotionId": "%s",
                  "customerId": "%s",
                  "workorderId": "%s",
                  "discountAmount": "15.00",
                  "discountType": "PERCENTAGE",
                  "promotionCode": "SPRING25",
                  "recordedOverLimit": %b
                }
                """.formatted(promotionId, customerId, workorderId, recordedOverLimit);
    }

    /**
     * Builds a minimal {@link PromotionRedemption} entity for direct repository seeding.
     * Avoids going through the service layer, allowing tests to pre-populate state
     * independently of the stub.
     *
     * @param promotionId       the promotion UUID
     * @param customerId        the customer UUID
     * @param workorderId       the workorder UUID
     * @param recordedOverLimit the over-limit flag value
     * @return unsaved entity ready for {@code saveAndFlush}
     */
    private PromotionRedemption buildRedemptionEntity(UUID promotionId, UUID customerId, UUID workorderId,
            boolean recordedOverLimit) {
        PromotionRedemption entity = new PromotionRedemption();
        entity.setPromotionId(promotionId);
        entity.setCustomerId(customerId);
        entity.setWorkorderId(workorderId);
        entity.setDiscountAmount(new BigDecimal("15.00"));
        entity.setDiscountType("PERCENTAGE");
        entity.setPromotionCode("SPRING25");
        entity.setRecordedOverLimit(recordedOverLimit);
        return entity;
    }
}
