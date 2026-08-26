package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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
    private static final String PRODUCT_ID = "01960003-0000-7000-8000-000000000081";
    private static final String LOCATION_ID = "01960003-0000-7000-8000-000000000082";
    private static final String DETAILED_ROW = "{\"productId\":\"" + PRODUCT_ID
            + "\",\"sku\":\"SKU-100\",\"name\":\"Heavy Duty Wrench\",\"lifecycleState\":\"ACTIVE\","
            + "\"msrpAmount\":\"99.99\",\"msrpCurrency\":\"USD\"}";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockRestServiceServer mockServer;
    private PricingFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("PricingFacadeTool." + toolMethod);
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tool result is not valid JSON: " + json, exception);
        }
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        FacadeContractManifest.Entry skuPrice = contract("getPriceForSku");
        tool = new PricingFacadeTool(
                builder,
                BASE_URL,
                skuPrice.leg("product").template(),
                skuPrice.leg("effectivePrice").template(),
                contract("getPromotionByCode").template(),
                contract("listPriceRestrictions").template(),
                contract("getPriceList").template());
    }

    @Test
    @DisplayName("getPriceForSku without a location searches the catalog and answers with the active MSRP")
    void getPriceForSku_withoutLocation_returnsMsrpFromDetailedSearch() {
        FacadeContractManifest.Entry product = contract("getPriceForSku").leg("product");
        mockServer
                .expect(requestTo(BASE_URL + product.expand(Map.of("sku", "SKU-100"))))
                .andExpect(method(product.httpMethod()))
                .andRespond(withSuccess("{\"data\":[" + DETAILED_ROW + "],\"limit\":20}", MediaType.APPLICATION_JSON));

        JsonNode envelope = parse(tool.getPriceForSku("SKU-100", null));

        mockServer.verify();
        assertThat(envelope.get("composition").asText()).isEqualTo("skuPrice");
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        JsonNode data = envelope.get("sections").get("product").get("data");
        assertThat(data.get("productId").asText()).isEqualTo(PRODUCT_ID);
        assertThat(data.get("msrpAmount").asText()).isEqualTo("99.99");
        assertThat(data.get("msrpCurrency").asText()).isEqualTo("USD");
        assertThat(envelope.get("sections").has("effectivePrice")).isFalse();
        assertThat(envelope.get("sources")).extracting(JsonNode::asText).containsExactly("product");
    }

    @Test
    @DisplayName("getPriceForSku with a location feeds the extracted productId into the effective-price leg")
    void getPriceForSku_withLocation_addsEffectivePriceLeg() {
        FacadeContractManifest.Entry skuPrice = contract("getPriceForSku");
        mockServer
                .expect(requestTo(BASE_URL + skuPrice.leg("product").expand(Map.of("sku", "SKU-100"))))
                .andExpect(method(skuPrice.leg("product").httpMethod()))
                .andRespond(withSuccess("{\"data\":[" + DETAILED_ROW + "],\"limit\":20}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL
                        + skuPrice.leg("effectivePrice")
                                .expand(Map.of("locationId", LOCATION_ID, "productId", PRODUCT_ID))))
                .andExpect(method(skuPrice.leg("effectivePrice").httpMethod()))
                .andRespond(withSuccess("{\"effectivePrice\":\"94.50\"}", MediaType.APPLICATION_JSON));

        JsonNode envelope = parse(tool.getPriceForSku("SKU-100", LOCATION_ID));

        mockServer.verify();
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.get("sections")
                        .get("product")
                        .get("data")
                        .get("msrpAmount")
                        .asText())
                .isEqualTo("99.99");
        assertThat(envelope.get("sections")
                        .get("effectivePrice")
                        .get("data")
                        .get("effectivePrice")
                        .asText())
                .isEqualTo("94.50");
        assertThat(envelope.get("sources")).extracting(JsonNode::asText).containsExactly("product", "effectivePrice");
    }

    @Test
    @DisplayName("getPriceForSku returns a clear not_found JSON when no product matches, and skips the price leg")
    void getPriceForSku_noMatch_returnsNotFoundWithoutSecondCall() {
        FacadeContractManifest.Entry product = contract("getPriceForSku").leg("product");
        mockServer
                .expect(requestTo(BASE_URL + product.expand(Map.of("sku", "SKU-MISSING"))))
                .andRespond(withSuccess("{\"data\":[],\"limit\":20}", MediaType.APPLICATION_JSON));

        JsonNode result = parse(tool.getPriceForSku("SKU-MISSING", LOCATION_ID));

        mockServer.verify();
        assertThat(result.get("status").asText()).isEqualTo("not_found");
        assertThat(result.get("sku").asText()).isEqualTo("SKU-MISSING");
        assertThat(result.get("message").asText()).contains("No catalog product matches");
    }

    @Test
    @DisplayName("getPriceForSku renders a 403 catalog search as a degraded not_authorized envelope, body unleaked")
    void getPriceForSku_forbiddenSearch_rendersNotAuthorized() {
        FacadeContractManifest.Entry product = contract("getPriceForSku").leg("product");
        mockServer
                .expect(requestTo(BASE_URL + product.expand(Map.of("sku", "SKU-100"))))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"secret\":\"FORBIDDEN-PAYLOAD\"}"));

        String rendered = tool.getPriceForSku("SKU-100", null);

        mockServer.verify();
        assertThat(rendered).doesNotContain("FORBIDDEN-PAYLOAD");
        JsonNode envelope = parse(rendered);
        assertThat(envelope.get("status").asText()).isEqualTo("degraded");
        assertThat(envelope.get("sections").get("product").get("status").asText())
                .isEqualTo("not_authorized");
        assertThat(envelope.get("sources")).isEmpty();
    }

    @Test
    @DisplayName("getPriceForSku keeps the MSRP answer when the effective-price leg fails")
    void getPriceForSku_failedEffectivePrice_degradesOnlyThatSection() {
        FacadeContractManifest.Entry skuPrice = contract("getPriceForSku");
        mockServer
                .expect(requestTo(BASE_URL + skuPrice.leg("product").expand(Map.of("sku", "SKU-100"))))
                .andRespond(withSuccess("{\"data\":[" + DETAILED_ROW + "],\"limit\":20}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL
                        + skuPrice.leg("effectivePrice")
                                .expand(Map.of("locationId", LOCATION_ID, "productId", PRODUCT_ID))))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        JsonNode envelope = parse(tool.getPriceForSku("SKU-100", LOCATION_ID));

        mockServer.verify();
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.get("sections")
                        .get("product")
                        .get("data")
                        .get("msrpAmount")
                        .asText())
                .isEqualTo("99.99");
        assertThat(envelope.get("sections").get("effectivePrice").get("status").asText())
                .isEqualTo("error");
        assertThat(envelope.get("sources")).extracting(JsonNode::asText).containsExactly("product");
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
