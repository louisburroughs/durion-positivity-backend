package com.positivity.price.internal.contract;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contract behavior tests for contextual price quote endpoint.
 *
 * <p>
 * Verifies Issue #51 behavior for quote pricing by product, location, and
 * customer tier.
 * </p>
 *
 * Issue: #51
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Price Quote Contract Behavior")
class PriceQuoteContractBehaviorIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("givenValidProduct_whenPriceQuoteRequested_thenReturns200WithBreakdown")
    void givenValidProduct_whenPriceQuoteRequested_thenReturns200WithBreakdown() throws Exception {
        ObjectNode request = baseValidRequest();

        mockMvc.perform(withGatewayAuth(post("/v1/price/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.unitPrice").exists())
                .andExpect(jsonPath("$.pricingBreakdown", not(empty())));
    }

    @Test
    @DisplayName("givenNoMatchingRules_whenPriceQuoteRequested_thenReturnsMsrpFallback")
    void givenNoMatchingRules_whenPriceQuoteRequested_thenReturnsMsrpFallback() throws Exception {
        ObjectNode request = baseValidRequest();
        request.put("productId", UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa").toString());
        request.put("locationId", UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb").toString());
        request.put("customerTierId", UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc").toString());

        mockMvc.perform(withGatewayAuth(post("/v1/price/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.priceSource").value("MSRP_FALLBACK"));
    }

    @Test
    @DisplayName("givenNonExistentProduct_whenPriceQuoteRequested_thenReturns404")
    void givenNonExistentProduct_whenPriceQuoteRequested_thenReturns404() throws Exception {
        ObjectNode request = baseValidRequest();
        request.put("productId", UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd").toString());

        mockMvc.perform(withGatewayAuth(post("/v1/price/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("givenZeroQuantity_whenPriceQuoteRequested_thenReturns400")
    void givenZeroQuantity_whenPriceQuoteRequested_thenReturns400() throws Exception {
        ObjectNode request = baseValidRequest();
        request.put("quantity", 0);

        mockMvc.perform(withGatewayAuth(post("/v1/price/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("givenMissingProductId_whenPriceQuoteRequested_thenReturns400")
    void givenMissingProductId_whenPriceQuoteRequested_thenReturns400() throws Exception {
        ObjectNode request = baseValidRequest();
        request.remove("productId");

        mockMvc.perform(withGatewayAuth(post("/v1/price/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private ObjectNode baseValidRequest() {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("productId", UUID.fromString("11111111-1111-1111-1111-111111111111").toString());
        request.put("quantity", 1);
        request.put("locationId", UUID.fromString("22222222-2222-2222-2222-222222222222").toString());
        request.put("customerTierId", UUID.fromString("33333333-3333-3333-3333-333333333333").toString());
        return request;
    }

    private MockHttpServletRequestBuilder withGatewayAuth(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder
                .header("X-User", "contract-test-user")
                .header("X-Authorities", "price:quotes:read,price:quotes:write")
                .header("X-Correlation-Id", "issue-51-contract-test");
    }
}