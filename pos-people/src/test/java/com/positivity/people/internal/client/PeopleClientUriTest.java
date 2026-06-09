package com.positivity.people.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PeopleClientUriTest {

    @Test
    void securityServiceClient_usesDirectSecurityDiscovery() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://security-service")
                .defaultHeader("X-User", "pos-people")
                .defaultHeader("X-Authorities", "security:user:view,security:role:view,security:role:assign");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://security-service/v1/users"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Authorities", "security:user:view,security:role:view,security:role:assign"))
                .andRespond(withSuccess(
                        "[{\"id\":\"00000000-0000-0000-0000-000000000001\",\"username\":\"alice\"}]",
                        MediaType.APPLICATION_JSON));

        SecurityServiceClient client = new SecurityServiceClient(builder.build(), Clock.systemUTC());
        assertThat(client.getUserByUsername("alice")).isPresent();
        server.verify();
    }

    @Test
    void locationReferenceClient_usesDirectLocationDiscovery() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://location")
                .defaultHeader("X-User", "pos-people")
                .defaultHeader("X-Authorities", "location:read");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        server.expect(requestTo("http://location/v1/locations/" + locationId + "/validation"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Authorities", "location:read"))
                .andRespond(withSuccess("{\"exists\":true,\"active\":true}", MediaType.APPLICATION_JSON));

        LocationReferenceClient client = new LocationReferenceClient(builder.build());
        assertThat(client.isLocationActive(locationId)).isTrue();
        server.verify();
    }

    @Test
    void workexecJobTimeClient_usesDirectWorkorderDiscovery() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://workorder")
                .defaultHeader("X-User", "pos-people")
                .defaultHeader("X-Authorities", "workorder:labor:view");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000010");

        server.expect(requestTo(
                        "http://workorder/v1/workexec/job-time-totals?startDate=2026-01-01&endDate=2026-01-31&timezone=UTC&locationId="
                                + locationId))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Authorities", "workorder:labor:view"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        WorkexecJobTimeClient client = new WorkexecJobTimeClient(builder.build());
        assertThat(client.getJobTimeTotals(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), "UTC",
                        locationId, List.of()))
                .isEmpty();
        server.verify();
    }
}
