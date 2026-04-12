package com.positivity.mcp.internal.orchestration.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link ExaWebSearchTool}: empty-key guard and request shape.
 */
class ExaWebSearchToolTest {

  private static final String BASE_URL = "https://api.exa.ai";

  @Test
  @DisplayName("webSearch with empty API key returns graceful unavailability message without throwing")
  void webSearch_withEmptyApiKey_returnsWarningMessage() {
    RestClient.Builder builder = RestClient.builder();
    ExaWebSearchTool tool = new ExaWebSearchTool(builder, BASE_URL, "", "auto", 5);

    String result = tool.webSearch("automotive oil filter");

    assertThat(result).contains("EXA_API_KEY is not configured").doesNotContain("Exception");
  }

  @Test
  @DisplayName("webSearch with valid API key sends POST /search with x-api-key header")
  void webSearch_withValidApiKey_sendsCorrectRequest() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

    mockServer.expect(requestTo(BASE_URL + "/search"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("x-api-key", "test-key-abc"))
        .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

    ExaWebSearchTool tool = new ExaWebSearchTool(builder, BASE_URL, "test-key-abc", "auto", 5);

    String result = tool.webSearch("brake pads");

    mockServer.verify();
    assertThat(result).isEqualTo("{\"results\":[]}");
  }
}
