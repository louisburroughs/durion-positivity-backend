package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link PricingFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class PricingFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String PRICE_BOOK_ID = "01960003-0000-7000-8000-000000000080";

    private MockRestServiceServer mockServer;
    private PricingFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("PricingFacadeTool." + toolMethod);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new PricingFacadeTool(
                builder,
                BASE_URL,
                contract("getPriceForSku").template(),
                contract("getPromotionByCode").template(),
                contract("listPriceRestrictions").template(),
                contract("getPriceList").template());
    }

    @Test
    @DisplayName("getPriceForSku sends GET /pricing/sku/{sku} and returns body")
    void getPriceForSku_sendsGetToSkuPriceEndpoint() {
        FacadeContractManifest.Entry entry = contract("getPriceForSku");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("sku", "SKU-100"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"sku\":\"SKU-100\",\"price\":19.99}", MediaType.APPLICATION_JSON));

        String result = tool.getPriceForSku("SKU-100");

        mockServer.verify();
        assertThat(result).isNotEmpty().contains("SKU-100");
    }

    @Test
    @DisplayName("getPromotionByCode sends GET /promotions/offers/by-code/{promoCode} and returns body")
    void getPromotionByCode_sendsGetToByCodeEndpoint() {
        FacadeContractManifest.Entry entry = contract("getPromotionByCode");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("promoCode", "SPRING10"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"promoCode\":\"SPRING10\"}", MediaType.APPLICATION_JSON));

        String result = tool.getPromotionByCode("SPRING10");

        mockServer.verify();
        assertThat(result).isNotEmpty().contains("SPRING10");
    }

    @Test
    @DisplayName("listPriceRestrictions sends GET /price/restrictions/rules and returns body")
    void listPriceRestrictions_sendsGetToRulesEndpoint() {
        FacadeContractManifest.Entry entry = contract("listPriceRestrictions");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of())))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        String result = tool.listPriceRestrictions();

        mockServer.verify();
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("getPriceList sends GET to the catalog price-book endpoint (ADR-0054) and returns body")
    void getPriceList_sendsGetToCatalogPriceBook() {
        FacadeContractManifest.Entry entry = contract("getPriceList");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("priceBookId", PRICE_BOOK_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"id\":\"" + PRICE_BOOK_ID + "\"}", MediaType.APPLICATION_JSON));

        String result = tool.getPriceList(PRICE_BOOK_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty().contains(PRICE_BOOK_ID);
    }
}
