package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Unit tests for {@link CustomerFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class CustomerFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String PARTY_ID = "01960003-0000-7000-8000-000000000020";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockRestServiceServer mockServer;
    private CustomerFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("CustomerFacadeTool." + toolMethod);
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
        FacadeContractManifest.Entry history = contract("getCustomerHistory");
        tool = new CustomerFacadeTool(
                builder,
                BASE_URL,
                contract("getCustomer").template(),
                contract("searchCustomers").template(),
                history.leg("snapshot").template(),
                history.leg("interactions").template(),
                history.leg("invoices").template(),
                history.leg("workorders").template());
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
    @DisplayName("getCustomerHistory composes snapshot, interactions, invoice lines, and workorders")
    void getCustomerHistory_composesAllFourLegs() {
        FacadeContractManifest.Entry history = contract("getCustomerHistory");
        Map<String, String> uriParams = Map.of("partyId", PARTY_ID);
        mockServer
                .expect(requestTo(BASE_URL + history.leg("snapshot").expand(uriParams)))
                .andExpect(method(history.leg("snapshot").httpMethod()))
                .andRespond(withSuccess("{\"partyId\":\"" + PARTY_ID + "\"}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL + history.leg("interactions").expand(uriParams)))
                .andExpect(method(history.leg("interactions").httpMethod()))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"CALL\"}]}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL + history.leg("invoices").expand(uriParams)))
                .andExpect(method(history.leg("invoices").httpMethod()))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL + history.leg("workorders").expand(uriParams)))
                .andExpect(method(history.leg("workorders").httpMethod()))
                .andRespond(withSuccess("[{\"workorderId\":\"WO-1\"}]", MediaType.APPLICATION_JSON));

        JsonNode envelope = parse(tool.getCustomerHistory(PARTY_ID));

        mockServer.verify();
        assertThat(envelope.get("composition").asText()).isEqualTo("customerHistory");
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.get("sections")
                        .get("snapshot")
                        .get("data")
                        .get("partyId")
                        .asText())
                .isEqualTo(PARTY_ID);
        assertThat(envelope.get("sections")
                        .get("interactions")
                        .get("data")
                        .get("content")
                        .get(0)
                        .get("type")
                        .asText())
                .isEqualTo("CALL");
        assertThat(envelope.get("sections").get("invoices").get("status").asText())
                .isEqualTo("ok");
        assertThat(envelope.get("sections")
                        .get("workorders")
                        .get("data")
                        .get(0)
                        .get("workorderId")
                        .asText())
                .isEqualTo("WO-1");
        assertThat(envelope.get("sources"))
                .extracting(JsonNode::asText)
                .containsExactly("snapshot", "interactions", "invoices", "workorders");
    }

    @Test
    @DisplayName("getCustomerHistory keeps answering when a leg is forbidden, without leaking its body")
    void getCustomerHistory_forbiddenLeg_rendersNotAuthorized() {
        FacadeContractManifest.Entry history = contract("getCustomerHistory");
        Map<String, String> uriParams = Map.of("partyId", PARTY_ID);
        mockServer
                .expect(requestTo(BASE_URL + history.leg("snapshot").expand(uriParams)))
                .andRespond(withSuccess("{\"partyId\":\"" + PARTY_ID + "\"}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL + history.leg("interactions").expand(uriParams)))
                .andRespond(withSuccess("{\"content\":[]}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL + history.leg("invoices").expand(uriParams)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"secret\":\"FORBIDDEN-PAYLOAD\"}"));
        mockServer
                .expect(requestTo(BASE_URL + history.leg("workorders").expand(uriParams)))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        String rendered = tool.getCustomerHistory(PARTY_ID);

        mockServer.verify();
        assertThat(rendered).doesNotContain("FORBIDDEN-PAYLOAD");
        JsonNode envelope = parse(rendered);
        // Every leg is optional, so a denied invoice leg degrades only its own section.
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.get("sections").get("invoices").get("status").asText())
                .isEqualTo("not_authorized");
        assertThat(envelope.get("sources"))
                .extracting(JsonNode::asText)
                .containsExactly("snapshot", "interactions", "workorders");
    }
}
