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
 * Unit tests for {@link AccountingFacadeTool}: verifies RestClient call shapes.
 */
class AccountingFacadeToolTest {

  private static final String BASE_URL = "http://pos-accounting/v1/accounting";

  private MockRestServiceServer mockServer;
  private AccountingFacadeTool tool;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    mockServer = MockRestServiceServer.bindTo(builder).build();
    tool = new AccountingFacadeTool(builder);
  }

  @Test
  @DisplayName("getAccountBalance sends GET /accounts/{accountId}/balance and returns body")
  void getAccountBalance_sendsGetToBalanceEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/accounts/ACC-001/balance"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"accountId\":\"ACC-001\",\"balance\":1000.00}", MediaType.APPLICATION_JSON));

    String result = tool.getAccountBalance("ACC-001");

    mockServer.verify();
    assertThat(result).isNotEmpty().contains("ACC-001");
  }

  @Test
  @DisplayName("searchJournalEntries sends GET /journal-entries/search?q={query} and returns body")
  void searchJournalEntries_sendsGetToSearchEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/journal-entries/search?q=revenue"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"entries\":[]}", MediaType.APPLICATION_JSON));

    String result = tool.searchJournalEntries("revenue");

    mockServer.verify();
    assertThat(result).isNotEmpty();
  }

  @Test
  @DisplayName("getFinancialSummary sends GET /summary/{period} and returns body")
  void getFinancialSummary_sendsGetToSummaryEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/summary/2025-Q1"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"period\":\"2025-Q1\"}", MediaType.APPLICATION_JSON));

    String result = tool.getFinancialSummary("2025-Q1");

    mockServer.verify();
    assertThat(result).isNotEmpty();
  }
}
