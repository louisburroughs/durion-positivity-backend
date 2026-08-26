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
 * Unit tests for {@link CustomerFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class CustomerFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String PARTY_ID = "01960003-0000-7000-8000-000000000020";

    private MockRestServiceServer mockServer;
    private CustomerFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("CustomerFacadeTool." + toolMethod);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new CustomerFacadeTool(
                builder,
                BASE_URL,
                contract("getCustomer").template(),
                contract("searchCustomers").template(),
                contract("getCustomerHistory").template());
    }

    @Test
    @DisplayName("getCustomer sends GET /crm/accounts/parties/{partyId} and returns body")
    void getCustomer_sendsGetToPartyIdentityEndpoint() {
        FacadeContractManifest.Entry entry = contract("getCustomer");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("partyId", PARTY_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"partyId\":\"" + PARTY_ID + "\"}", MediaType.APPLICATION_JSON));

        String result = tool.getCustomer(PARTY_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty().contains(PARTY_ID);
    }

    @Test
    @DisplayName("searchCustomers sends GET /crm/accounts/parties?name={query} and returns body")
    void searchCustomers_sendsGetToDirectoryBrowse() {
        FacadeContractManifest.Entry entry = contract("searchCustomers");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("query", "Smith"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"content\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchCustomers("Smith");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getCustomerHistory sends GET /customers/{customerId}/history and returns body")
    void getCustomerHistory_sendsGetToHistoryEndpoint() {
        FacadeContractManifest.Entry entry = contract("getCustomerHistory");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("customerId", PARTY_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"events\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getCustomerHistory(PARTY_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }
}
