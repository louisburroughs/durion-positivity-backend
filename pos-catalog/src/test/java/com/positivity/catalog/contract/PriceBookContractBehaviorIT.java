package com.positivity.catalog.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseIntegrationTest;
import com.positivity.catalog.internal.dto.CatalogItemRequestDto;
import com.positivity.catalog.service.CatalogService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@DisplayName("Price Book Contract Behavioral Tests")
class PriceBookContractBehaviorIT extends BaseIntegrationTest {

    @Autowired
    private CatalogService catalogService;

    @Test
    @DisplayName("CP-167-010: Create price book, add global rule, and resolve rule price")
    void resolvePriceFromGlobalRule() throws Exception {
        UUID productId = createProductAndReturnId("CAP167 PriceBook Product A");
        String priceBookId = createPriceBook("CAP167 Company Default Book", "COMPANY_DEFAULT", null, true);

        mockMvc.perform(withAuth(post("/v1/products/price-books/{priceBookId}/rules", priceBookId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "targetType", "GLOBAL",
                        "pricingLogic", "{\"amount\":\"59.9900\",\"currency\":\"USD\"}",
                        "conditionType", "NONE",
                        "priority", 10,
                        "effectiveStartAt", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString(),
                        "createdByUserId", UUID.randomUUID().toString()))))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(post("/v1/products/price-books/resolve-price")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "productId", productId.toString(),
                        "priceBookId", priceBookId,
                        "asOf", LocalDate.now().toString())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PRICE_BOOK_RULE"))
                .andExpect(jsonPath("$.resolvedAmount").value("59.9900"));
    }

    @Test
    @DisplayName("CP-167-011: SKU rule takes precedence over global rule")
    void skuRuleBeatsGlobalRule() throws Exception {
        UUID productId = createProductAndReturnId("CAP167 PriceBook Product B");
        String priceBookId = createPriceBook("CAP167 Scope Book", "COMPANY_DEFAULT", null, true);

        mockMvc.perform(withAuth(post("/v1/products/price-books/{priceBookId}/rules", priceBookId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "targetType", "GLOBAL",
                        "pricingLogic", "{\"amount\":\"49.9900\",\"currency\":\"USD\"}",
                        "conditionType", "NONE",
                        "priority", 1,
                        "effectiveStartAt", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString(),
                        "createdByUserId", UUID.randomUUID().toString()))))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(post("/v1/products/price-books/{priceBookId}/rules", priceBookId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "targetType", "SKU",
                        "targetId", productId.toString(),
                        "pricingLogic", "{\"amount\":\"39.9900\",\"currency\":\"USD\"}",
                        "conditionType", "NONE",
                        "priority", 1,
                        "effectiveStartAt", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString(),
                        "createdByUserId", UUID.randomUUID().toString()))))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(post("/v1/products/price-books/resolve-price")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "productId", productId.toString(),
                        "priceBookId", priceBookId,
                        "asOf", LocalDate.now().toString())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PRICE_BOOK_RULE"))
                .andExpect(jsonPath("$.resolvedAmount").value("39.9900"));
    }

    @Test
    @DisplayName("VE-167-010: Reject conflicting overlapping price book rule")
    void rejectConflictingRule() throws Exception {
        UUID productId = createProductAndReturnId("CAP167 PriceBook Product C");
        String priceBookId = createPriceBook("CAP167 Conflict Book", "COMPANY_DEFAULT", null, true);

        mockMvc.perform(withAuth(post("/v1/products/price-books/{priceBookId}/rules", priceBookId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "targetType", "SKU",
                        "targetId", productId.toString(),
                        "pricingLogic", "{\"amount\":\"29.9900\",\"currency\":\"USD\"}",
                        "conditionType", "NONE",
                        "priority", 5,
                        "effectiveStartAt", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString(),
                        "effectiveEndAt", OffsetDateTime.now(ZoneOffset.UTC).plusDays(5).toString(),
                        "createdByUserId", UUID.randomUUID().toString()))))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(post("/v1/products/price-books/{priceBookId}/rules", priceBookId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "targetType", "SKU",
                        "targetId", productId.toString(),
                        "pricingLogic", "{\"amount\":\"24.9900\",\"currency\":\"USD\"}",
                        "conditionType", "NONE",
                        "priority", 10,
                        "effectiveStartAt", OffsetDateTime.now(ZoneOffset.UTC).toString(),
                        "effectiveEndAt", OffsetDateTime.now(ZoneOffset.UTC).plusDays(8).toString(),
                        "createdByUserId", UUID.randomUUID().toString()))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("LC-167-010: Deactivate rule and fall back to MSRP")
    void deactivateRuleFallsBackToMsrp() throws Exception {
        UUID productId = createProductAndReturnId("CAP167 PriceBook Product D");
        String priceBookId = createPriceBook("CAP167 Fallback Book", "COMPANY_DEFAULT", null, true);

        mockMvc.perform(withAuth(post("/v1/products/{productId}/msrp", productId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "amount", "88.8800",
                        "currency", "USD",
                        "effectiveStartDate", LocalDate.now().minusDays(1).toString(),
                        "createdByUserId", UUID.randomUUID().toString()))))
                .andExpect(status().isCreated());

        MvcResult ruleResult = mockMvc
                .perform(withAuth(post("/v1/products/price-books/{priceBookId}/rules", priceBookId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetType", "GLOBAL",
                                "pricingLogic", "{\"amount\":\"44.4400\",\"currency\":\"USD\"}",
                                "conditionType", "NONE",
                                "priority", 2,
                                "effectiveStartAt", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString(),
                                "createdByUserId", UUID.randomUUID().toString()))))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> createdRule = objectMapper.readValue(ruleResult.getResponse().getContentAsString(),
                Map.class);
        String ruleId = String.valueOf(createdRule.get("ruleId"));

        mockMvc.perform(withAuth(delete("/v1/products/price-books/{priceBookId}/rules/{ruleId}", priceBookId, ruleId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(withAuth(post("/v1/products/price-books/resolve-price")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "productId", productId.toString(),
                        "priceBookId", priceBookId,
                        "asOf", LocalDate.now().toString())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MSRP"))
                .andExpect(jsonPath("$.resolvedAmount").value("88.8800"));
    }

    private String createPriceBook(String name, String scope, String scopeId, boolean isDefault) throws Exception {
        var payload = new java.util.HashMap<String, Object>();
        payload.put("name", name);
        payload.put("scope", scope);
        payload.put("isDefault", isDefault);
        if (scopeId != null) {
            payload.put("scopeId", scopeId);
        }

        MvcResult result = mockMvc.perform(withAuth(post("/v1/products/price-books"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return String.valueOf(body.get("priceBookId"));
    }

    private UUID createProductAndReturnId(String name) {
        CatalogItemRequestDto request = new CatalogItemRequestDto();
        request.setName(name);
        request.setShortDescription("Short " + name);
        request.setLongDescription("Long " + name);
        request.setType("PHYSICAL");
        return catalogService.addCatalogItem("product", request).getId();
    }
}
