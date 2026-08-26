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
 * Unit tests for {@link AccountingFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class AccountingFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String GL_ACCOUNT_ID = "01960003-0000-7000-8000-000000000001";

    private MockRestServiceServer mockServer;
    private AccountingFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("AccountingFacadeTool." + toolMethod);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new AccountingFacadeTool(
                builder,
                BASE_URL,
                contract("getAccountBalance").template(),
                contract("getGeneralLedger").template(),
                contract("getFinancialSummary").template());
    }

    @Test
    @DisplayName("getAccountBalance sends GET /gl-accounts/{glAccountId}/balance and returns body")
    void getAccountBalance_sendsGetToBalanceEndpoint() {
        FacadeContractManifest.Entry entry = contract("getAccountBalance");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("glAccountId", GL_ACCOUNT_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess(
                        "{\"glAccountId\":\"" + GL_ACCOUNT_ID + "\",\"balance\":1000.00}", MediaType.APPLICATION_JSON));

        String result = tool.getAccountBalance(GL_ACCOUNT_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty().contains(GL_ACCOUNT_ID);
    }

    @Test
    @DisplayName("getGeneralLedger without accountId sends GET general-ledger?startDate&endDate")
    void getGeneralLedger_withoutAccount_sendsDateRangeOnly() {
        FacadeContractManifest.Entry entry = contract("getGeneralLedger");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("startDate", "2026-01-01", "endDate", "2026-01-31"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"rows\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getGeneralLedger("2026-01-01", "2026-01-31", null);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getGeneralLedger with accountId appends the optional accountId query param")
    void getGeneralLedger_withAccount_appendsAccountId() {
        FacadeContractManifest.Entry entry = contract("getGeneralLedger");
        mockServer
                .expect(requestTo(BASE_URL
                        + entry.expand(Map.of("startDate", "2026-01-01", "endDate", "2026-01-31"))
                        + "&accountId=" + GL_ACCOUNT_ID))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"rows\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getGeneralLedger("2026-01-01", "2026-01-31", GL_ACCOUNT_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getFinancialSummary sends GET /summary/{period} and returns body")
    void getFinancialSummary_sendsGetToSummaryEndpoint() {
        FacadeContractManifest.Entry entry = contract("getFinancialSummary");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("period", "2025-Q1"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"period\":\"2025-Q1\"}", MediaType.APPLICATION_JSON));

        String result = tool.getFinancialSummary("2025-Q1");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }
}
