package com.positivity.bulkloader.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Pins the URI {@link RestResolutionContext} puts on the wire.
 *
 * <p>Every resolution builds its URI with {@code UriComponentsBuilder.encode()}, so what reaches
 * the context is already percent-encoded. {@code RestClient}'s default encoding mode encoded it a
 * second time, so a lookup for {@code Phyllis Long} left as {@code name=Phyllis%2520Long} and
 * arrived at pos-customer as the literal string {@code Phyllis%20Long} — which matches no party.
 * Every owner name containing a space resolved to nothing, and the rows then failed with
 * "accountId is required", a message about the fixture for a fault in this client. All 329 vehicle
 * rows were lost to it on alpha before anyone read the receiving service's own request log.
 *
 * <p>Asserting on the request URI is the point: the defect was invisible in this service's logs,
 * which printed the URI as the caller built it, not as it was sent.
 */
class RestResolutionContextUriEncodingTest {

    private static final UUID JOB_LOCATION = UUID.randomUUID();

    @Test
    @DisplayName("a space in a query value is sent encoded exactly once")
    void spaceInQueryValue_isNotDoubleEncoded() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String uri = UriComponentsBuilder.fromPath("/v1/crm/accounts/parties")
                .queryParam("name", "Phyllis Long")
                .queryParam("partyType", "PERSON")
                .encode(StandardCharsets.UTF_8)
                .toUriString();

        server.expect(requestTo("http://customer/v1/crm/accounts/parties?name=Phyllis%20Long&partyType=PERSON"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        RestResolutionContext context = new RestResolutionContext(builder, noHeaders(), JOB_LOCATION);
        Optional<Map> body = context.get("customer", uri, Map.class);

        assertThat(body).isPresent();
        server.verify();
    }

    @Test
    @DisplayName("an ampersand and a slash in a query value survive the round trip")
    void reservedCharactersInQueryValue_areSentEncodedOnce() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        String uri = UriComponentsBuilder.fromPath("/v1/crm/accounts/parties")
                .queryParam("name", "Blue Stone Aggregate & Gravel LLC")
                .encode(StandardCharsets.UTF_8)
                .toUriString();

        server.expect(requestTo(
                        "http://customer/v1/crm/accounts/parties?name=Blue%20Stone%20Aggregate%20%26%20Gravel%20LLC"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        RestResolutionContext context = new RestResolutionContext(builder, noHeaders(), JOB_LOCATION);
        assertThat(context.get("customer", uri, Map.class)).isPresent();
        server.verify();
    }

    /** The relay is exercised elsewhere; this test is about the URI, so it sends no headers. */
    private static AuthorizationHeaderRelay noHeaders() {
        return new AuthorizationHeaderRelay(null) {
            @Override
            public void apply(RequestHeadersSpec<?> requestSpec) {
                // no-op
            }
        };
    }
}
