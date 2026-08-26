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
 * Unit tests for {@link LocationFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class LocationFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String LOCATION_ID = "01960003-0000-7000-8000-000000000060";
    private static final String ROSTER = """
            [
              {"id":"loc-1","name":"Downtown Garage","code":"DTG"},
              {"id":"loc-2","name":"Airport Service Center","code":"ASC"},
              {"id":"loc-3","name":"Garage Annex","code":"GAX"}
            ]
            """;

    private MockRestServiceServer mockServer;
    private LocationFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("LocationFacadeTool." + toolMethod);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new LocationFacadeTool(
                builder,
                BASE_URL,
                contract("getLocation").template(),
                contract("searchLocations").template(),
                contract("getLocationInventory").template());
    }

    @Test
    @DisplayName("getLocation sends GET /locations/{locationId} and returns body")
    void getLocation_sendsGetToLocationEndpoint() {
        FacadeContractManifest.Entry entry = contract("getLocation");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("locationId", LOCATION_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"id\":\"" + LOCATION_ID + "\"}", MediaType.APPLICATION_JSON));

        String result = tool.getLocation(LOCATION_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty().contains(LOCATION_ID);
    }

    @Test
    @DisplayName("searchLocations fetches the roster and contains-filters by name case-insensitively")
    void searchLocations_filtersRosterByName() {
        FacadeContractManifest.Entry entry = contract("searchLocations");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of())))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess(ROSTER, MediaType.APPLICATION_JSON));

        String result = tool.searchLocations("garage");

        mockServer.verify();
        assertThat(result).contains("Downtown Garage").contains("Garage Annex");
        assertThat(result).doesNotContain("Airport Service Center");
    }

    @Test
    @DisplayName("searchLocations also matches the code field")
    void searchLocations_filtersRosterByCode() {
        FacadeContractManifest.Entry entry = contract("searchLocations");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of())))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess(ROSTER, MediaType.APPLICATION_JSON));

        String result = tool.searchLocations("asc");

        mockServer.verify();
        assertThat(result).contains("Airport Service Center");
        assertThat(result).doesNotContain("Downtown Garage");
    }

    @Test
    @DisplayName("getLocationInventory sends GET to the cross-domain inventory-inquiry endpoint")
    void getLocationInventory_sendsGetToInventoryInquiry() {
        FacadeContractManifest.Entry entry = contract("getLocationInventory");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("locationId", LOCATION_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getLocationInventory(LOCATION_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }
}
