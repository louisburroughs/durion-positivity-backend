package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class CustomerReferenceServiceTest {

    @Test
    void searchIdsByName_returnsPartyIdsFromBrowse() throws Exception {
        UUID p1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID p2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
        String payload = """
                {"results":[
                  {"partyId":"%s","displayName":"Acme Corp","legalName":"Acme Corporation Inc"},
                  {"partyId":"%s","displayName":"","legalName":"Beta LLC"}
                ]}
                """.formatted(p1, p2);
        HttpServer server = startServer("/v1/crm/accounts/parties", 200, payload);
        try {
            String serviceId = "localhost:" + server.getAddress().getPort();
            CustomerReferenceService service = new CustomerReferenceService(RestClient.builder(), serviceId);

            List<CustomerReferenceService.CustomerRef> refs = service.searchIdsByName("acme", 10);

            assertThat(refs).hasSize(2);
            assertThat(refs)
                    .extracting(CustomerReferenceService.CustomerRef::customerId)
                    .containsExactly(p1, p2);
            assertThat(refs.get(0).customerName()).isNotBlank();
            assertThat(refs.get(0).customerName()).isEqualTo("Acme Corp");
            assertThat(refs.get(1).customerName()).isEqualTo("Beta LLC");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchIdsByName_failsSoftToEmptyOnError() throws Exception {
        HttpServer server = startServer("/v1/crm/accounts/parties", 500, "boom");
        try {
            String serviceId = "localhost:" + server.getAddress().getPort();
            CustomerReferenceService service = new CustomerReferenceService(RestClient.builder(), serviceId);

            List<CustomerReferenceService.CustomerRef> refs = service.searchIdsByName("acme", 10);

            assertThat(refs).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchIdsByName_returnsEmpty_forBlankName() {
        CustomerReferenceService service = new CustomerReferenceService(RestClient.builder(), "localhost:1");

        assertThat(service.searchIdsByName("   ", 10)).isEmpty();
        assertThat(service.searchIdsByName(null, 10)).isEmpty();
    }

    private HttpServer startServer(String expectedPath, int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String requestPath = exchange.getRequestURI().getPath();
            if (!"GET".equals(exchange.getRequestMethod()) || !expectedPath.equals(requestPath)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        return server;
    }
}
