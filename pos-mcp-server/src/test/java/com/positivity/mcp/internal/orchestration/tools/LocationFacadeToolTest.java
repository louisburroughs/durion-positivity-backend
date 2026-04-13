package com.positivity.mcp.internal.orchestration.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link LocationFacadeTool}: verifies RestClient call shapes.
 */
class LocationFacadeToolTest {

  private static final String BASE_URL = "http://pos-location/v1/locations";

  private MockRestServiceServer mockServer;
  private LocationFacadeTool tool;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    mockServer = MockRestServiceServer.bindTo(builder).build();
    tool = new LocationFacadeTool(builder, BASE_URL);
  }

  @Test
  @DisplayName("getLocation sends GET /{locationId} and returns body")
  void getLocation_sendsGetToLocationEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/LOC-001"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"id\":\"LOC-001\"}", MediaType.APPLICATION_JSON));

    String result = tool.getLocation("LOC-001");

    mockServer.verify();
    assertThat(result).isNotEmpty().contains("LOC-001");
  }

  @Test
  @DisplayName("searchLocations sends GET /search?q={query} and returns body")
  void searchLocations_sendsGetToSearchEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/search?q=downtown"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

    String result = tool.searchLocations("downtown");

    mockServer.verify();
    assertThat(result).isNotEmpty();
  }

  @Test
  @DisplayName("getLocationInventory sends GET /{locationId}/inventory and returns body")
  void getLocationInventory_sendsGetToInventoryEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/LOC-001/inventory"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"locationId\":\"LOC-001\",\"stock\":[]}", MediaType.APPLICATION_JSON));

    String result = tool.getLocationInventory("LOC-001");

    mockServer.verify();
    assertThat(result).isNotEmpty();
  }
}
