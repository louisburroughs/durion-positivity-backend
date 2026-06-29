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

/**
 * HTTP-level tests for {@link CustomerReferenceService}. Both {@code resolve} and {@code resolveAll}
 * now route through the batch endpoint {@code POST /v1/crm/accounts/parties:resolve}; name and phone
 * derivation lives in pos-customer, so these tests assert the workorder-side mapping only.
 */
class CustomerReferenceServiceHttpTest {

    private static final String RESOLVE_PATH = "/v1/crm/accounts/parties:resolve";

    @Test
    void resolve_mapsNameAndPhone_fromBatchResponse() throws Exception {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startBatchServer(
                RESOLVE_PATH,
                200,
                "[{\"partyId\":\"" + customerId + "\",\"displayName\":\"Jane Doe\",\"phoneNumber\":\"+1-555-0100\"}]",
                callCount);
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
    void resolve_returnsFallback_whenBatchOmitsId() throws Exception {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startBatchServer(RESOLVE_PATH, 200, "[]", callCount);
        try {
            String serviceId = "localhost:" + server.getAddress().getPort();
            CustomerReferenceService service = new CustomerReferenceService(RestClient.builder(), serviceId);

            CustomerReferenceService.CustomerContact contact = service.resolve(customerId);

            assertThat(contact.name()).isEqualTo("customer-" + customerId);
            assertThat(contact.phoneNumber()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolve_callsBatchPath_withAuthorityHeader() throws Exception {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startBatchServerCapturingHeaders(
                RESOLVE_PATH,
                200,
                "[{\"partyId\":\"" + customerId + "\",\"displayName\":\"Jane Doe\"}]",
                callCount,
                headers -> {
                    assertThat(headers.getFirst("X-User")).isEqualTo("pos-workorder");
                    assertThat(headers.getFirst("X-Authorities")).isEqualTo("crm:party:view");
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

    @Test
    void resolveAll_usesSingleBatchCall_andDeDuplicatesRepeatedIds() throws Exception {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startBatchServer(
                RESOLVE_PATH,
                200,
                "[{\"partyId\":\"" + customerId + "\",\"displayName\":\"Repeated Customer\"}]",
                callCount);
        try {
            String serviceId = "localhost:" + server.getAddress().getPort();
            CustomerReferenceService service = new CustomerReferenceService(RestClient.builder(), serviceId);

            var resolved = service.resolveAll(List.of(customerId, customerId));

            assertThat(resolved).hasSize(1);
            assertThat(resolved.get(customerId).name()).isEqualTo("Repeated Customer");
            // one batch POST regardless of repeated input ids
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer startBatchServer(String expectedPath, int status, String body, AtomicInteger callCount)
            throws IOException {
        return startBatchServerCapturingHeaders(expectedPath, status, body, callCount, headers -> {});
    }

    private HttpServer startBatchServerCapturingHeaders(
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
            if (!"POST".equals(exchange.getRequestMethod()) || !expectedPath.equals(requestPath)) {
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
}
