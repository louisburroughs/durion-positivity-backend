package com.positivity.customer.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Verifies that PeopleClient calls the correct direct Eureka service URL
 * (http://{serviceId}/v1/...) with the required auth headers.
 */
class CustomerClientUriTest {

    // -----------------------------------------------------------------------
    // PeopleClient
    // -----------------------------------------------------------------------

    @Test
    void peopleClient_resolveOrCreate_usesEurekaUrl_withAuthHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        PeopleClient client = new PeopleClient(builder, "people");

        mockServer
                .expect(requestTo("http://people/v1/people/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User", "pos-customer"))
                .andExpect(header("X-Authorities", "people:person:create"))
                .andRespond(withSuccess(
                        "{\"personId\":\"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\"}", MediaType.APPLICATION_JSON));

        UUID result = client.resolveOrCreatePersonId("test@example.com", "555-1234", "Doe", "John");

        assertThat(result).isEqualTo(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        mockServer.verify();
    }

    @Test
    void peopleClient_fetchPersonIdentities_deserializesPosPeopleContactPoints() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        PeopleClient client = new PeopleClient(builder, "people");

        // pos-people serializes the contact-point flag as JSON "primary" (not "isPrimary").
        // Regression: a primitive-boolean field mapped from the wrong name bound null and
        // threw MismatchedInputException, collapsing all contact reads to local fallback.
        UUID personId = UUID.fromString("01960025-0000-7000-8000-000000000001");
        String json = "[{\"id\":\"" + personId + "\",\"firstName\":\"Greg\",\"lastName\":\"Whitfield\","
                + "\"primaryEmail\":\"g.whitfield@example.com\",\"contactPoints\":["
                + "{\"contactType\":\"EMAIL\",\"value\":\"g.whitfield@example.com\",\"primary\":true},"
                + "{\"contactType\":\"PHONE_WORK\",\"value\":\"704-555-3001\",\"primary\":false}]}]";

        mockServer
                .expect(requestTo("http://people/v1/people/by-ids"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Authorities", "people:person:view"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        var identities = client.fetchPersonIdentities(java.util.List.of(personId));

        assertThat(identities).containsKey(personId);
        var identity = identities.get(personId);
        assertThat(identity.displayName()).isEqualTo("Greg Whitfield");
        assertThat(identity.emails()).containsExactly("g.whitfield@example.com");
        assertThat(identity.phones()).containsExactly("704-555-3001");
        mockServer.verify();
    }

    @Test
    void peopleClient_fetchPersonIdentities_emptyContactPoints_fallsBackToPrimaryEmail() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        PeopleClient client = new PeopleClient(builder, "people");

        UUID personId = UUID.fromString("01960024-0000-7000-8000-000000000001");
        String json = "[{\"id\":\"" + personId + "\",\"firstName\":\"Marcus\",\"lastName\":\"Patterson\","
                + "\"primaryEmail\":\"marcus@example.com\",\"contactPoints\":[]}]";

        mockServer
                .expect(requestTo("http://people/v1/people/by-ids"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        var identity = client.fetchPersonIdentities(java.util.List.of(personId)).get(personId);

        assertThat(identity.contactPoints()).isEmpty();
        assertThat(identity.emails()).containsExactly("marcus@example.com"); // falls back to primaryEmail
        assertThat(identity.phones()).isEmpty();
        mockServer.verify();
    }

    @Test
    void peopleClient_fetchPersonIdentities_toleratesUnknownPosPeopleFields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        PeopleClient client = new PeopleClient(builder, "people");

        UUID personId = UUID.fromString("01960024-0000-7000-8000-000000000002");
        // Full pos-people Person payload with fields PersonView does not declare.
        String json = "[{\"id\":\"" + personId + "\",\"firstName\":\"Jane\",\"lastName\":\"Smith\","
                + "\"primaryEmail\":\"jane@example.com\",\"secondaryEmail\":\"j2@example.com\","
                + "\"phoneNumbers\":[\"555-1\"],\"username\":\"jsmith\",\"employeeStatus\":\"ACTIVE\","
                + "\"contactPoints\":[{\"contactType\":\"EMAIL\",\"value\":\"jane@example.com\",\"primary\":true}]}]";

        mockServer
                .expect(requestTo("http://people/v1/people/by-ids"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        var identity = client.fetchPersonIdentities(java.util.List.of(personId)).get(personId);

        assertThat(identity.displayName()).isEqualTo("Jane Smith");
        assertThat(identity.emails()).containsExactly("jane@example.com");
        mockServer.verify();
    }

    @Test
    void peopleClient_fetchPersonIdentities_emptyInput_makesNoCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        PeopleClient client = new PeopleClient(builder, "people");

        assertThat(client.fetchPersonIdentities(java.util.List.of())).isEmpty();
        mockServer.verify(); // no request expected
    }

    @Test
    void peopleClient_fetchPersonIdentitiesQuietly_returnsEmptyOnServerError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        PeopleClient client = new PeopleClient(builder, "people");

        UUID personId = UUID.fromString("01960025-0000-7000-8000-000000000001");
        mockServer.expect(requestTo("http://people/v1/people/by-ids")).andRespond(withServerError());

        // Quiet variant degrades to empty instead of throwing (display read paths).
        assertThat(client.fetchPersonIdentitiesQuietly(java.util.List.of(personId)))
                .isEmpty();
        mockServer.verify();
    }

    @Test
    void peopleClient_setContactPoints_putsToContactPointsUrl_withPrimaryField() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        PeopleClient client = new PeopleClient(builder, "people");

        UUID personId = UUID.fromString("01960025-0000-7000-8000-000000000001");
        mockServer
                .expect(requestTo("http://people/v1/people/" + personId + "/contact-points"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-Authorities", "people:person:edit"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(
                        "$[0].primary", org.hamcrest.Matchers.is(true)))
                .andRespond(withSuccess());

        client.setContactPoints(
                personId, java.util.List.of(new PeopleClient.ContactPointUpsert("EMAIL", "g@example.com", true)));

        mockServer.verify();
    }

    @Test
    void peopleClient_setContactPoints_swallowsErrors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        PeopleClient client = new PeopleClient(builder, "people");

        UUID personId = UUID.fromString("01960025-0000-7000-8000-000000000002");
        mockServer
                .expect(requestTo("http://people/v1/people/" + personId + "/contact-points"))
                .andRespond(withServerError());

        // Best-effort dual-write: must not throw even if pos-people errors.
        client.setContactPoints(
                personId, java.util.List.of(new PeopleClient.ContactPointUpsert("EMAIL", "g@example.com", true)));

        mockServer.verify();
    }
}
