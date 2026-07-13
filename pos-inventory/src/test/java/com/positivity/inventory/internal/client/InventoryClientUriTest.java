package com.positivity.inventory.internal.client;

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

class InventoryClientUriTest {

    @Test
    void workorderValidationClient_usesDirectWorkorderDiscovery() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID workorderId = UUID.randomUUID();

        server.expect(requestTo("http://workorder/v1/workorders/" + workorderId + "/detail"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-inventory"))
                .andExpect(header("X-Authorities", "workorder:workorder:view"))
                .andRespond(withSuccess("{\"status\":\"OPEN\",\"parts\":[]}", MediaType.APPLICATION_JSON));

        WorkorderValidationClient client = new WorkorderValidationClient(builder, "workorder");
        try {
            client.getWorkorderLineValidation(
                    workorderId.toString(), UUID.randomUUID().toString());
        } catch (Exception ignored) {
        }
        server.verify();
    }
}
