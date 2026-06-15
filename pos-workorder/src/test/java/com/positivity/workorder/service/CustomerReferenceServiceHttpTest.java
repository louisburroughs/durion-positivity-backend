package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.internal.service.CustomerReferenceService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class CustomerReferenceServiceHttpTest {

    @Test
    void resolve_parsesNameAndPhone_fromDataEnvelope() throws Exception {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String payload = """
                {"data":{"customerName":"Jane Doe","phoneNumber":"+1-555-0100"}}
                """;
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServer("/v1/crm/" + customerId, 200, payload, callCount);
        try {
            String serviceId = "localhost:" + server.getAddress().getPort();
            CustomerReferenceService service = new CustomerReferenceService(RestClient.builder(), serviceId);

            CustomerReferenceService.CustomerContact contact = service.resolve(customerId);

            assertThat(contact.name()).isEqualTo("Jane Doe");
            assertThat(contact.phoneNumber()).isEqualTo("+1-555-0100");
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolve_returnsFallback_whenRemoteReturns404() throws Exception {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServer("/v1/crm/" + customerId, 404, "{}", callCount);
        try {
            String serviceId = "localhost:" + server.getAddress().getPort();
            CustomerReferenceService service = new CustomerReferenceService(RestClient.builder(), serviceId);

            CustomerReferenceService.CustomerContact contact = service.resolve(customerId);

            assertThat(contact.name()).isEqualTo("customer-" + customerId);
            assertThat(contact.phoneNumber()).isNull();
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolveAll_deDuplicatesRequests_forRepeatedIds() throws Exception {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServer(
                "/v1/crm/" + customerId,
                200,
                "{\"customerName\":\"Repeated Customer\",\"phone\":\"+1-555-2222\"}",
                callCount);
        try {
            String serviceId = "localhost:" + server.getAddress().getPort();
            CustomerReferenceService service = new CustomerReferenceService(RestClient.builder(), serviceId);

            var resolved = service.resolveAll(List.of(customerId, customerId));

            assertThat(resolved).hasSize(1);
            assertThat(resolved.get(customerId).name()).isEqualTo("Repeated Customer");
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolve_callsNativeCustomerPath_withAuthorityHeader() throws Exception {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServerCapturingHeaders(
                "/v1/crm/" + customerId, 200, "{\"customerName\":\"Jane Doe\"}", callCount, receivedHeaders -> {
                    assertThat(receivedHeaders.getFirst("X-User")).isEqualTo("pos-workorder");
                    assertThat(receivedHeaders.getFirst("X-Authorities")).isEqualTo("crm:party:view");
                });
        try {
            String serviceId = "localhost:" + server.getAddress().getPort();
            CustomerReferenceService service = new CustomerReferenceService(RestClient.builder(), serviceId);

            CustomerReferenceService.CustomerContact contact = service.resolve(customerId);

            assertThat(contact.name()).isEqualTo("Jane Doe");
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer startServerCapturingHeaders(
            String expectedPath,
            int status,
            String body,
            AtomicInteger callCount,
            java.util.function.Consumer<com.sun.net.httpserver.Headers> headerAssertions)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            callCount.incrementAndGet();
            String requestPath = exchange.getRequestURI().getPath();
            if (!"GET".equals(exchange.getRequestMethod()) || !expectedPath.equals(requestPath)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            headerAssertions.accept(exchange.getRequestHeaders());

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

    private HttpServer startServer(String expectedPath, int status, String body, AtomicInteger callCount)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            callCount.incrementAndGet();
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
