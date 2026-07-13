package com.positivity.shopmanager.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ShopManagerClientUriTest {

    @Test
    void locationClient_usesDirectLocationDiscovery() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://location/v1/locations/bays"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-shop-manager"))
                .andExpect(header("X-Authorities", "location:bay:read"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        LocationClient client = new LocationClient(builder, "location");
        client.getBays();
        server.verify();
    }

    @Test
    void locationClient_updateBays_executesPutRequestAndReturnsOriginalPayload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String requestBody = "[{\"name\":\"BAY-01\"}]";

        server.expect(requestTo("http://location/v1/locations/bays"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-User", "pos-shop-manager"))
                .andExpect(header("X-Authorities", "location:bay:manage"))
                .andRespond(withSuccess());

        LocationClient client = new LocationClient(builder, "location");
        Object response = client.updateBays(requestBody);

        assertThat(response).isSameAs(requestBody);
        server.verify();
    }

    @Test
    void locationClient_deleteBay_executesDeleteRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://location/v1/locations/10/bays/20"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("X-User", "pos-shop-manager"))
                .andExpect(header("X-Authorities", "location:bay:manage"))
                .andRespond(withSuccess());

        LocationClient client = new LocationClient(builder, "location");
        client.deleteBay(10L, 20L);

        server.verify();
    }

    @Test
    void locationClient_updateMobileUnits_executesPutRequestAndReturnsOriginalPayload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String requestBody = "[{\"name\":\"MOBILE-01\"}]";

        server.expect(requestTo("http://location/v1/locations/mobileUnit"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-User", "pos-shop-manager"))
                .andExpect(header("X-Authorities", "location:mobile-unit:manage"))
                .andRespond(withSuccess());

        LocationClient client = new LocationClient(builder, "location");
        Object response = client.updateMobileUnits(requestBody);

        assertThat(response).isSameAs(requestBody);
        server.verify();
    }

    @Test
    void locationClient_deleteMobileUnit_executesDeleteRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://location/v1/locations/30/mobileUnit/40"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("X-User", "pos-shop-manager"))
                .andExpect(header("X-Authorities", "location:mobile-unit:manage"))
                .andRespond(withSuccess());

        LocationClient client = new LocationClient(builder, "location");
        client.deleteMobileUnit(30L, 40L);

        server.verify();
    }

    @Test
    void locationClient_updateBays_propagatesServerErrors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://location/v1/locations/bays"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withServerError());

        LocationClient client = new LocationClient(builder, "location");

        assertThatThrownBy(() -> client.updateBays("{}"))
                .isInstanceOf(org.springframework.web.client.RestClientResponseException.class);
        server.verify();
    }

    @Test
    void locationClient_deleteBay_propagatesServerErrors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://location/v1/locations/55/bays/66"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withServerError());

        LocationClient client = new LocationClient(builder, "location");

        assertThatThrownBy(() -> client.deleteBay(55L, 66L))
                .isInstanceOf(org.springframework.web.client.RestClientResponseException.class);
        server.verify();
    }

    @Test
    void locationClient_updateMobileUnits_propagatesServerErrors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://location/v1/locations/mobileUnit"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withServerError());

        LocationClient client = new LocationClient(builder, "location");

        assertThatThrownBy(() -> client.updateMobileUnits("{}"))
                .isInstanceOf(org.springframework.web.client.RestClientResponseException.class);
        server.verify();
    }

    @Test
    void locationClient_deleteMobileUnit_propagatesServerErrors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://location/v1/locations/77/mobileUnit/88"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withServerError());

        LocationClient client = new LocationClient(builder, "location");

        assertThatThrownBy(() -> client.deleteMobileUnit(77L, 88L))
                .isInstanceOf(org.springframework.web.client.RestClientResponseException.class);
        server.verify();
    }

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
