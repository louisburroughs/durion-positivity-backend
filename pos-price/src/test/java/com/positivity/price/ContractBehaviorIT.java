package com.positivity.price;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Price Management Backend Contract Behavioral Tests")
class ContractBehaviorIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ========== HAPPY PATH TESTS ==========
    @Test
    @DisplayName("CP-001: Successfully create pricing policy with valid dates and price")
    void testCreatePricingPolicy_HappyPath() throws Exception {
        String payload = createPricingPayload("PROD-001", "99.99", "USD", "2025-01-01", "2025-12-31");
        mockMvc.perform(post("/api/v1/prices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.productId").value("PROD-001"))
                .andExpect(jsonPath("$.price").value(99.99));
    }

    @Test
    @DisplayName("CP-002: Successfully retrieve pricing policy by ID")
    void testGetPricingPolicy_HappyPath() throws Exception {
        String payload = createPricingPayload("PROD-002", "149.50", "EUR", "2025-02-01", "2025-11-30");
        MvcResult createResult = mockMvc.perform(post("/api/v1/prices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String priceId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(get("/api/v1/prices/{id}", priceId)
                .header("X-Correlation-Id", "test-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(priceId))
                .andExpect(jsonPath("$.currency").value("EUR"));
    }

    // ========== VALIDATION ERROR TESTS ==========
    @Test
    @DisplayName("VE-001: Reject pricing with negative price amount")
    void testCreatePricingPolicy_NegativePrice() throws Exception {
        String invalidPayload = createPricingPayload("PROD-003", "-50.00", "USD", "2025-01-01", "2025-12-31");
        mockMvc.perform(post("/api/v1/prices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
                .header("X-Correlation-Id", "test-ve-001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("VE-002: Reject pricing with effectiveFrom after effectiveTo")
    void testCreatePricingPolicy_InvalidDateRange() throws Exception {
        String invalidPayload = createPricingPayload("PROD-004", "79.99", "GBP", "2025-12-31", "2025-01-01");
        mockMvc.perform(post("/api/v1/prices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
                .header("X-Correlation-Id", "test-ve-002"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("VE-003: Reject pricing with invalid currency code")
    void testCreatePricingPolicy_InvalidCurrency() throws Exception {
        String invalidPayload = createPricingPayload("PROD-005", "89.99", "INVALID", "2025-01-01", "2025-12-31");
        mockMvc.perform(post("/api/v1/prices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
                .header("X-Correlation-Id", "test-ve-003"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ========== IDEMPOTENCY TEST ==========
    @Test
    @DisplayName("ID-001: Idempotent create with same Idempotency-Key returns same resource")
    void testCreatePricingPolicy_Idempotent() throws Exception {
        String idempotencyKey = "idem-price-" + System.currentTimeMillis();
        String payload = createPricingPayload("PROD-006", "199.99", "JPY", "2025-03-01", "2025-09-30");

        MvcResult result1 = mockMvc.perform(post("/api/v1/prices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-id-001")
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isCreated())
                .andReturn();

        String id1 = objectMapper.readTree(result1.getResponse().getContentAsString()).get("id").asText();

        MvcResult result2 = mockMvc.perform(post("/api/v1/prices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-id-001")
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isCreated())
                .andReturn();

        String id2 = objectMapper.readTree(result2.getResponse().getContentAsString()).get("id").asText();
        assert id1.equals(id2) : "Idempotent requests should return same price policy ID";
    }

    // ========== CONCURRENCY INVARIANT TESTS ==========
    @Test
    @DisplayName("CC-001: Optimistic locking prevents concurrent updates with stale version")
    void testUpdatePricingPolicy_OptimisticLockingConflict() throws Exception {
        String payload = createPricingPayload("PROD-007", "249.99", "CAD", "2025-04-01", "2025-08-31");
        MvcResult createResult = mockMvc.perform(post("/api/v1/prices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-cc-001"))
                .andExpect(status().isCreated())
                .andReturn();

        String priceId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String updatePayload = "{\"price\":\"299.99\",\"version\":0}";

        mockMvc.perform(patch("/api/v1/prices/{id}", priceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload)
                .header("X-Correlation-Id", "test-cc-001")
                .header("If-Match", "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("CC-002: Date invariant enforced (effectiveFrom <= effectiveTo)")
    void testUpdatePricingPolicy_DateInvariant() throws Exception {
        String payload = createPricingPayload("PROD-008", "159.99", "USD", "2025-05-01", "2025-10-31");
        MvcResult createResult = mockMvc.perform(post("/api/v1/prices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-cc-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String priceId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String invalidUpdate = "{\"effectiveFrom\":\"2025-10-31\",\"effectiveTo\":\"2025-05-01\"}";

        mockMvc.perform(patch("/api/v1/prices/{id}", priceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidUpdate)
                .header("X-Correlation-Id", "test-cc-002"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    // ========== FIELD FORMAT VALIDATION TESTS ==========
    @Test
    @DisplayName("FF-001: ISO 8601 date format for effective dates")
    void testPricingPolicy_DateFormat() throws Exception {
        String payload = createPricingPayload("PROD-009", "119.99", "USD", "2025-06-01", "2025-09-30");
        MvcResult result = mockMvc.perform(post("/api/v1/prices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-ff-001"))
                .andExpect(status().isCreated())
                .andReturn();

        String effectiveFrom = objectMapper.readTree(result.getResponse().getContentAsString()).get("effectiveFrom")
                .asText();
        assert effectiveFrom.matches("\\d{4}-\\d{2}-\\d{2}") : "effectiveFrom must be ISO 8601 date format";
    }

    @Test
    @DisplayName("FF-002: Valid currency ISO 4217 codes")
    void testPricingPolicy_ValidCurrencyCodes() throws Exception {
        String[] validCurrencies = { "USD", "EUR", "GBP", "JPY", "CAD" };
        for (String currency : validCurrencies) {
            String payload = createPricingPayload("PROD-" + currency, "79.99", currency, "2025-07-01", "2025-08-31");
            mockMvc.perform(post("/api/v1/prices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload)
                    .header("X-Correlation-Id", "test-ff-002-" + currency))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.currency").value(currency));
        }
    }

    private String createPricingPayload(String productId, String price, String currency, String effectiveFrom,
            String effectiveTo) {
        return String.format(
                "{\"productId\":\"%s\",\"price\":%s,\"currency\":\"%s\",\"effectiveFrom\":\"%s\",\"effectiveTo\":\"%s\"}",
                productId, price, currency, effectiveFrom, effectiveTo);
    }
}
