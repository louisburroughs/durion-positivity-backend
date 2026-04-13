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
 * Unit tests for {@link WorkorderFacadeTool}: verifies RestClient call shapes.
 */
class WorkorderFacadeToolTest {

  private static final String BASE_URL = "http://pos-workorder/v1/workorders";

  private MockRestServiceServer mockServer;
  private WorkorderFacadeTool tool;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    mockServer = MockRestServiceServer.bindTo(builder).build();
    tool = new WorkorderFacadeTool(builder, BASE_URL);
  }

  @Test
  @DisplayName("getWorkorder sends GET /{workorderId} and returns body")
  void getWorkorder_sendsGetToWorkorderEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/WO-001"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"id\":\"WO-001\"}", MediaType.APPLICATION_JSON));

    String result = tool.getWorkorder("WO-001");

    mockServer.verify();
    assertThat(result).isNotEmpty().contains("WO-001");
  }

  @Test
  @DisplayName("searchWorkorders sends GET /search?q={query} and returns body")
  void searchWorkorders_sendsGetToSearchEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/search?q=OPEN"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

    String result = tool.searchWorkorders("OPEN");

    mockServer.verify();
    assertThat(result).isNotEmpty();
  }

  @Test
  @DisplayName("getWorkorderStatus sends GET /{workorderId}/status and returns body")
  void getWorkorderStatus_sendsGetToStatusEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/WO-001/status"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"status\":\"IN_PROGRESS\"}", MediaType.APPLICATION_JSON));

    String result = tool.getWorkorderStatus("WO-001");

    mockServer.verify();
    assertThat(result).isNotEmpty();
  }
}
