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
 * Unit tests for {@link InventoryFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 * Targets are fixed by ADR-0057 / V36 (availability vs on-hand permission split).
 */
class InventoryFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String LOCATION_ID = "01960003-0000-7000-8000-000000000040";

    private MockRestServiceServer mockServer;
    private InventoryFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("InventoryFacadeTool." + toolMethod);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new InventoryFacadeTool(
                builder,
                BASE_URL,
                contract("checkStock").template(),
                contract("searchInventory").template(),
                contract("getLocationStock").template(),
                "/inventory/v1/inventory/replenishment/policies");
    }

    @Test
    @DisplayName("checkStock sends GET /availability/by-sku?productSku={productSku} and returns body")
    void checkStock_sendsGetToAvailabilityBySku() {
        FacadeContractManifest.Entry entry = contract("checkStock");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("productSku", "SKU-100"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("[{\"productSku\":\"SKU-100\",\"available\":5}]", MediaType.APPLICATION_JSON));

        String result = tool.checkStock("SKU-100");

        mockServer.verify();
        assertThat(result).isNotEmpty().contains("SKU-100");
    }

    @Test
    @DisplayName("searchInventory without location sends the availability lookup with productSku only")
    void searchInventory_withoutLocation_sendsProductSkuOnly() {
        FacadeContractManifest.Entry entry = contract("searchInventory");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("productSku", "SKU-100"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("[{\"productSku\":\"SKU-100\"}]", MediaType.APPLICATION_JSON));

        String result = tool.searchInventory("SKU-100", null);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("searchInventory with location appends locationId and sourceType=WAREHOUSE")
    void searchInventory_withLocation_appendsWarehouseNarrowing() {
        FacadeContractManifest.Entry entry = contract("searchInventory");
        mockServer
                .expect(requestTo(BASE_URL
                        + entry.expand(Map.of("productSku", "SKU-100"))
                        + "&locationId=" + LOCATION_ID + "&sourceType=WAREHOUSE"))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("[{\"productSku\":\"SKU-100\"}]", MediaType.APPLICATION_JSON));

        String result = tool.searchInventory("SKU-100", LOCATION_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getLocationStock sends GET /locations/{locationId}/inventory-inquiry and returns body")
    void getLocationStock_sendsGetToInventoryInquiry() {
        FacadeContractManifest.Entry entry = contract("getLocationStock");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("locationId", LOCATION_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getLocationStock(LOCATION_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }
}
