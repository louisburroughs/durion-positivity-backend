package com.positivity.shopmanager.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ShopManagerClientUriTest {

        @Test
        void crmCustomerClient_usesDirectCustomerDiscovery() {
                RestClient.Builder builder = RestClient.builder().baseUrl("http://customer");
                MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
                UUID customerId = UUID.randomUUID();

                server.expect(requestTo("http://customer/v1/crm/" + customerId))
                                .andExpect(method(HttpMethod.GET))
                                .andExpect(header("X-User", "pos-shop-manager"))
                                .andExpect(header("X-Authorities", "crm:party:view"))
                                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

                CrmCustomerClient client = new CrmCustomerClient(builder.build());
                assertThat(client.getCustomerById(customerId)).isEmpty();
                server.verify();
        }

        @Test
        void crmVehicleClient_usesDirectCustomerDiscovery() {
                RestClient.Builder builder = RestClient.builder().baseUrl("http://customer");
                MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
                UUID vehicleId = UUID.randomUUID();

                server.expect(requestTo("http://customer/v1/crm/snapshot/vehicle/" + vehicleId))
                                .andExpect(method(HttpMethod.GET))
                                .andExpect(header("X-User", "pos-shop-manager"))
                                .andExpect(header("X-Authorities", "crm:vehicle:view"))
                                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

                CrmVehicleClient client = new CrmVehicleClient(builder.build());
                assertThat(client.getVehicleById(vehicleId)).isEmpty();
                server.verify();
        }

        @Test
        void hrAvailabilityClient_overlay_usesDirectPeopleDiscovery() {
                RestClient.Builder builder = RestClient.builder().baseUrl("http://people");
                MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

                server.expect(requestTo("http://people/v1/people/availability?locationId=loc-1&date=2026-01-01"))
                                .andExpect(method(HttpMethod.GET))
                                .andExpect(header("X-User", "pos-shop-manager"))
                                .andExpect(header("X-Authorities", "people:availability:view"))
                                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

                HrAvailabilityClient client = new HrAvailabilityClient(builder.build());
                client.getAvailabilityOverlay("loc-1", LocalDate.parse("2026-01-01"));
                server.verify();
        }

        @Test
        void hrAvailabilityClient_schedules_usesDirectPeopleDiscovery() {
                RestClient.Builder builder = RestClient.builder().baseUrl("http://people");
                MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

                server.expect(
                                requestTo(
                                                "http://people/hr/v1/schedules?personId=person-1&startTime=2026-01-01T00:00:00Z&endTime=2026-01-02T00:00:00Z"))
                                .andExpect(method(HttpMethod.GET))
                                .andExpect(header("X-User", "pos-shop-manager"))
                                .andExpect(header("X-Authorities", "people:availability:view"))
                                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

                HrAvailabilityClient client = new HrAvailabilityClient(builder.build());
                assertThat(client.getScheduleBlocks(
                                "person-1",
                                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                                java.time.Instant.parse("2026-01-02T00:00:00Z")))
                                .isEmpty();
                server.verify();
        }

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
        void personClient_usesDirectPeopleDiscovery() {
                RestClient.Builder builder = RestClient.builder();
                MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

                server.expect(requestTo("http://people/v1/people/42"))
                                .andExpect(method(HttpMethod.GET))
                                .andExpect(header("X-User", "pos-shop-manager"))
                                .andExpect(header("X-Authorities", "people:person:view"))
                                .andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));

                PersonClient client = new PersonClient(builder, "people");
                assertThat(client.getPersonById(42L).getId()).isEqualTo(42L);
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
