package com.positivity.securityservice.internal.client;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SecurityServiceClientUriTest {

    @Test
    void customerRegistrationClient_usesNativeCustomerPath() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://customer")
                .defaultHeader("X-User", "pos-security-service")
                .defaultHeader("X-Authorities", "crm:person:read");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://customer/v1/crm/persons?limit=10&offset=0"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-security-service"))
                .andExpect(header("X-Authorities", "crm:person:read"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        CustomerRegistrationClient client = new CustomerRegistrationClient(builder.build());
        client.searchPersons(null, null, null);
        server.verify();
    }
}
