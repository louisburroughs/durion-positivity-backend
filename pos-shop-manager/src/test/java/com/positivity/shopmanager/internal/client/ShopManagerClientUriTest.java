package com.positivity.shopmanager.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ShopManagerClientUriTest {

    @Test
    void serviceEntityClient_usesDirectCatalogDiscovery() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://catalog/v1/services/7"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-shop-manager"))
                .andExpect(header("X-Authorities", "catalog:product:read"))
                .andRespond(withSuccess("{\"id\":7}", MediaType.APPLICATION_JSON));

        ServiceEntityClient client = new ServiceEntityClient(builder, "catalog");
        assertThat(client.getServiceById(7L).getId()).isEqualTo(7L);
        server.verify();
    }
}
