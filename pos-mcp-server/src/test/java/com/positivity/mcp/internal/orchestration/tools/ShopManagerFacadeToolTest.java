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
 * Unit tests for {@link ShopManagerFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class ShopManagerFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String SHOP_ID = "01960003-0000-7000-8000-0000000000c0";

    private MockRestServiceServer mockServer;
    private ShopManagerFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("ShopManagerFacadeTool." + toolMethod);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new ShopManagerFacadeTool(
                builder,
                BASE_URL,
                contract("getShopStatus").template(),
                contract("getShopQueue").template(),
                contract("searchShops").template());
    }

    @Test
    @DisplayName("getShopStatus sends GET /shop/{shopId}/status and returns body")
    void getShopStatus_sendsGetToStatusEndpoint() {
        FacadeContractManifest.Entry entry = contract("getShopStatus");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("shopId", SHOP_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"status\":\"OPEN\"}", MediaType.APPLICATION_JSON));

        String result = tool.getShopStatus(SHOP_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getShopQueue sends GET /shop/{shopId}/queue and returns body")
    void getShopQueue_sendsGetToQueueEndpoint() {
        FacadeContractManifest.Entry entry = contract("getShopQueue");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("shopId", SHOP_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"queue\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getShopQueue(SHOP_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("searchShops fetches the location roster and contains-filters by name/code")
    void searchShops_filtersLocationRoster() {
        FacadeContractManifest.Entry entry = contract("searchShops");
        String roster = """
                [
                  {"id":"loc-1","name":"Downtown Garage","code":"DTG"},
                  {"id":"loc-2","name":"Airport Service Center","code":"ASC"}
                ]
                """;
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of())))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess(roster, MediaType.APPLICATION_JSON));

        String result = tool.searchShops("downtown");

        mockServer.verify();
        assertThat(result).contains("Downtown Garage");
        assertThat(result).doesNotContain("Airport Service Center");
    }
}
