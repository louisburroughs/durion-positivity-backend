package com.positivity.invoice.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link CustomerReferenceClient}. Binds {@link MockRestServiceServer} to the
 * {@link RestClient.Builder} so the by-name search and the batch {@code parties:resolve} mapping
 * (and silent-failure behaviour) are exercised without a live CRM service.
 */
class CustomerReferenceClientTest {

    private static final String BASE_URL = "http://pos-customer:8080/v1/crm";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private CustomerReferenceClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CustomerReferenceClient(builder, BASE_URL);
    }

    @Test
    void searchIdsByName_blankQuery_returnsEmptyWithoutCall() {
        assertThat(client.searchIdsByName("  ", 50)).isEmpty();
        server.verify(); // no HTTP expectation registered => no call made
    }

    @Test
    void searchIdsByName_extractsPartyIds_skippingNonMapRows() {
        server.expect(requestTo(Matchers.containsString("/accounts/parties?name=Acme&size=50")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"results\":[{\"partyId\":\"p-1\"},\"junk\",{\"noId\":true},{\"partyId\":\" p-2 \"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.searchIdsByName("Acme", 50)).containsExactly("p-1", "p-2");
        server.verify();
    }

    @Test
    void resolveNames_emptyInput_returnsEmptyWithoutCall() {
        assertThat(client.resolveNames(java.util.List.of())).isEmpty();
        server.verify();
    }

    @Test
    void resolveNames_batchPost_mapsRows() {
        server.expect(requestTo(BASE_URL + "/accounts/parties:resolve"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "[{\"partyId\":\"p-1\",\"displayName\":\" Acme Towing LLC \"},"
                                + "{\"partyId\":\"p-2\",\"displayName\":\"Beta Co\"}]",
                        MediaType.APPLICATION_JSON));

        assertThat(client.resolveNames(java.util.List.of("p-1", "p-2")))
                .containsExactly(Map.entry("p-1", "Acme Towing LLC"), Map.entry("p-2", "Beta Co"));
        server.verify();
    }

    @Test
    void resolveNames_skipsRowsWithBlankNameOrId() {
        server.expect(requestTo(BASE_URL + "/accounts/parties:resolve"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "[{\"partyId\":\"p-1\",\"displayName\":\"\"},{\"displayName\":\"orphan\"},"
                                + "{\"partyId\":\"p-2\",\"displayName\":\"Kept\"}]",
                        MediaType.APPLICATION_JSON));

        assertThat(client.resolveNames(java.util.List.of("p-1", "p-2"))).containsExactly(Map.entry("p-2", "Kept"));
        server.verify();
    }

    @Test
    void resolveNames_dedupesAndSkipsBlankIds() {
        // one POST expected; the request body carries the single distinct, non-blank id
        server.expect(requestTo(BASE_URL + "/accounts/parties:resolve"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withSuccess("[{\"partyId\":\"p-1\",\"displayName\":\"Once\"}]", MediaType.APPLICATION_JSON));

        Map<String, String> resolved = client.resolveNames(java.util.Arrays.asList("p-1", "p-1", "  ", null));

        assertThat(resolved).containsExactly(Map.entry("p-1", "Once"));
        server.verify();
    }

    @Test
    void resolveNames_remoteError_isSwallowed_returningEmptyMap() {
        server.expect(requestTo(BASE_URL + "/accounts/parties:resolve")).andRespond(withServerError());

        assertThat(client.resolveNames(java.util.List.of("p-1"))).isEmpty();
        server.verify();
    }
}
