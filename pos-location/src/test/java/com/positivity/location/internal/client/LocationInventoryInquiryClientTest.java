package com.positivity.location.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LocationInventoryInquiryClientTest {

    @Test
    void getOnHandQuantity_usesDirectInventoryDiscovery_andAuthHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID locationId = UUID.randomUUID();

        server.expect(requestTo("http://inventory/v1/inventory/locations/" + locationId + "/inventory-inquiry"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Authorities", "inventory:on_hand:view"))
                .andExpect(header("X-User", "pos-location"))
                .andRespond(withSuccess("{\"onHandQuantity\": 5}", MediaType.APPLICATION_JSON));

        LocationInventoryInquiryClient client = new LocationInventoryInquiryClient(builder, "inventory");
        assertThat(client.getOnHandQuantity(locationId)).isEqualTo(5);
        server.verify();
    }
}
