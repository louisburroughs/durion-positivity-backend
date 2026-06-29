package com.positivity.invoice.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
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
 * {@link RestClient.Builder} the client builds from, so the data-envelope unwrap, name-field
 * fallback precedence, name composition, and silent-failure behaviour are exercised without a
 * live CRM service.
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
    void resolveNames_unwrapsDataEnvelope_andAppliesFieldPrecedence() {
        // customerName wins over displayName/legalName/name
        server.expect(requestTo(BASE_URL + "/p-1"))
                .andRespond(withSuccess(
                        "{\"data\":{\"customerName\":\"Acme Towing LLC\",\"displayName\":\"ignored\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.resolveNames(List.of("p-1"))).containsEntry("p-1", "Acme Towing LLC");
        server.verify();
    }

    @Test
    void resolveNames_fallsBackThroughNameFields() {
        // no customerName -> displayName used
        server.expect(requestTo(BASE_URL + "/p-1"))
                .andRespond(withSuccess(
                        "{\"displayName\":\"Display Co\",\"legalName\":\"Legal Co\"}", MediaType.APPLICATION_JSON));

        assertThat(client.resolveNames(List.of("p-1"))).containsEntry("p-1", "Display Co");
        server.verify();
    }

    @Test
    void resolveNames_composesFirstLast_whenOnlyPersonFieldsPresent() {
        server.expect(requestTo(BASE_URL + "/p-1"))
                .andRespond(
                        withSuccess("{\"firstName\":\"John\",\"lastName\":\"Smith\"}", MediaType.APPLICATION_JSON));

        assertThat(client.resolveNames(List.of("p-1"))).containsEntry("p-1", "John Smith");
        server.verify();
    }

    @Test
    void resolveNames_dedupesContainedName() {
        // lastName already contains firstName -> return lastName, not "John John Smith"
        server.expect(requestTo(BASE_URL + "/p-1"))
                .andRespond(withSuccess(
                        "{\"firstName\":\"John\",\"lastName\":\"John Smith\"}", MediaType.APPLICATION_JSON));

        assertThat(client.resolveNames(List.of("p-1"))).containsEntry("p-1", "John Smith");
        server.verify();
    }

    @Test
    void resolveNames_omitsIdWhenBodyEmptyOrUnresolved() {
        server.expect(requestTo(BASE_URL + "/p-1")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.resolveNames(List.of("p-1"))).doesNotContainKey("p-1");
        server.verify();
    }

    @Test
    void resolveNames_remoteError_isSwallowed_returningNoEntry() {
        server.expect(requestTo(BASE_URL + "/p-1")).andRespond(withServerError());

        assertThat(client.resolveNames(List.of("p-1"))).isEmpty();
        server.verify();
    }

    @Test
    void resolveNames_skipsBlankAndDuplicateIds() {
        // only one HTTP call expected for the single distinct, non-blank id
        server.expect(requestTo(BASE_URL + "/p-1"))
                .andRespond(withSuccess("{\"name\":\"Once\"}", MediaType.APPLICATION_JSON));

        Map<String, String> resolved = client.resolveNames(java.util.Arrays.asList("p-1", "p-1", "  ", null));

        assertThat(resolved).containsExactly(Map.entry("p-1", "Once"));
        server.verify();
    }
}
