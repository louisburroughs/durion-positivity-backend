package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * Unit tests for {@link AccountingFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class AccountingFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String GL_ACCOUNT_ID = "01960003-0000-7000-8000-000000000001";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockRestServiceServer mockServer;
    private AccountingFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("AccountingFacadeTool." + toolMethod);
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
        FacadeContractManifest.Entry summary = contract("getFinancialSummary");
        tool = new AccountingFacadeTool(
                builder,
                BASE_URL,
                contract("getAccountBalance").template(),
                contract("getGeneralLedger").template(),
                summary.leg("incomeStatement").template(),
                summary.leg("balanceSheet").template(),
                summary.leg("trialBalance").template());
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
    @DisplayName("getFinancialSummary composes income statement, balance sheet, and trial balance for the period")
    void getFinancialSummary_composesAllThreeReports() {
        FacadeContractManifest.Entry summary = contract("getFinancialSummary");
        FacadeContractManifest.Entry incomeStatement = summary.leg("incomeStatement");
        FacadeContractManifest.Entry balanceSheet = summary.leg("balanceSheet");
        FacadeContractManifest.Entry trialBalance = summary.leg("trialBalance");
        mockServer
                .expect(requestTo(
                        BASE_URL + incomeStatement.expand(Map.of("startDate", "2026-03-01", "endDate", "2026-03-31"))))
                .andExpect(method(incomeStatement.httpMethod()))
                .andRespond(withSuccess("{\"revenue\":1000}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL + balanceSheet.expand(Map.of("asOfDate", "2026-03-31"))))
                .andExpect(method(balanceSheet.httpMethod()))
                .andRespond(withSuccess("{\"assets\":5000}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL + trialBalance.expand(Map.of("asOf", "2026-03-31"))))
                .andExpect(method(trialBalance.httpMethod()))
                .andRespond(withSuccess("{\"rows\":[]}", MediaType.APPLICATION_JSON));

        JsonNode envelope = parse(tool.getFinancialSummary("2026-03"));

        mockServer.verify();
        assertThat(envelope.get("composition").asText()).isEqualTo("financialSummary");
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.get("sections")
                        .get("incomeStatement")
                        .get("data")
                        .get("revenue")
                        .asInt())
                .isEqualTo(1000);
        assertThat(envelope.get("sections")
                        .get("balanceSheet")
                        .get("data")
                        .get("assets")
                        .asInt())
                .isEqualTo(5000);
        assertThat(envelope.get("sections").get("trialBalance").get("status").asText())
                .isEqualTo("ok");
        assertThat(envelope.get("sources"))
                .extracting(JsonNode::asText)
                .containsExactly("incomeStatement", "balanceSheet", "trialBalance");
    }

    @Test
    @DisplayName("getFinancialSummary renders a 403 balance-sheet leg as not_authorized without leaking the body")
    void getFinancialSummary_forbiddenLeg_rendersNotAuthorized() {
        FacadeContractManifest.Entry summary = contract("getFinancialSummary");
        mockServer
                .expect(requestTo(BASE_URL
                        + summary.leg("incomeStatement")
                                .expand(Map.of("startDate", "2026-03-01", "endDate", "2026-03-31"))))
                .andRespond(withSuccess("{\"revenue\":1000}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL + summary.leg("balanceSheet").expand(Map.of("asOfDate", "2026-03-31"))))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"secret\":\"FORBIDDEN-PAYLOAD\"}"));
        mockServer
                .expect(requestTo(BASE_URL + summary.leg("trialBalance").expand(Map.of("asOf", "2026-03-31"))))
                .andRespond(withSuccess("{\"rows\":[]}", MediaType.APPLICATION_JSON));

        String rendered = tool.getFinancialSummary("2026-03");

        mockServer.verify();
        assertThat(rendered).doesNotContain("FORBIDDEN-PAYLOAD");
        JsonNode envelope = parse(rendered);
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.get("sections").get("balanceSheet").get("status").asText())
                .isEqualTo("not_authorized");
        assertThat(envelope.get("sources"))
                .extracting(JsonNode::asText)
                .containsExactly("incomeStatement", "trialBalance");
    }

    @Test
    @DisplayName("getFinancialSummary degrades when the required income-statement leg fails")
    void getFinancialSummary_failedIncomeStatement_degradesEnvelope() {
        FacadeContractManifest.Entry summary = contract("getFinancialSummary");
        mockServer
                .expect(requestTo(BASE_URL
                        + summary.leg("incomeStatement")
                                .expand(Map.of("startDate", "2026-01-01", "endDate", "2026-12-31"))))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        mockServer
                .expect(requestTo(BASE_URL + summary.leg("balanceSheet").expand(Map.of("asOfDate", "2026-12-31"))))
                .andRespond(withSuccess("{\"assets\":5000}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL + summary.leg("trialBalance").expand(Map.of("asOf", "2026-12-31"))))
                .andRespond(withSuccess("{\"rows\":[]}", MediaType.APPLICATION_JSON));

        JsonNode envelope = parse(tool.getFinancialSummary("2026"));

        mockServer.verify();
        assertThat(envelope.get("status").asText()).isEqualTo("degraded");
        assertThat(envelope.get("sections").get("incomeStatement").get("status").asText())
                .isEqualTo("error");
    }

    @Test
    @DisplayName("getFinancialSummary rejects an unsupported period form without issuing a request")
    void getFinancialSummary_rejectsUnsupportedPeriod() {
        assertThatThrownBy(() -> tool.getFinancialSummary("2025-Q1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM")
                .hasMessageContaining("YYYY");

        mockServer.verify();
    }
}
