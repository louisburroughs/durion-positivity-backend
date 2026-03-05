package com.positivity.workorder.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link ShopmgrOperationalContextClient}.
 *
 * <p>
 * Uses an embedded {@link HttpServer} to exercise real RestClient paths,
 * including 200 success, 404 fallback, non-404 rethrow, and connection
 * failures.
 */
class ShopmgrOperationalContextClientTest {

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    // -----------------------------------------------------------------------
    // getOperationalContext
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getOperationalContext returns context on 200 OK")
    void getOperationalContext_returnsContext_on200() throws Exception {
        String body = """
                {
                  "version": "1",
                  "locationId": "00000000-0000-0000-0000-000000000002",
                  "bayId": "BAY-1",
                  "locked": false
                }
                """;
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServer(200, body, callCount);
        try {
            ShopmgrOperationalContextClient client = buildClient(server);

            var context = client.getOperationalContext(WORKORDER_ID);

            assertThat(context).isNotNull();
            assertThat(context.getBayId()).isEqualTo("BAY-1");
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("getOperationalContext rethrows RestClientResponseException on 4xx")
    void getOperationalContext_rethrowsRestClientResponseException_on400() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServer(400, "{}", callCount);
        try {
            ShopmgrOperationalContextClient client = buildClient(server);

            assertThatThrownBy(() -> client.getOperationalContext(WORKORDER_ID))
                    .isInstanceOf(RestClientResponseException.class);
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("getOperationalContext wraps ResourceAccessException in IllegalStateException")
    void getOperationalContext_throwsIllegalStateException_onConnectionRefused() {
        // Port 1 is reserved/not listening — triggers ResourceAccessException
        ShopmgrOperationalContextClient client = new ShopmgrOperationalContextClient(
                RestClient.builder(), "http://localhost:1");

        assertThatThrownBy(() -> client.getOperationalContext(WORKORDER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Shopmgr unavailable");
    }

    // -----------------------------------------------------------------------
    // getBayStatusForLocation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getBayStatusForLocation returns bay list on 200 OK")
    void getBayStatusForLocation_returnsBayList_on200() throws Exception {
        String body = """
                [
                  {"bayId": "00000000-0000-0000-0000-000000000010", "bayName": "Bay 1", "status": "OPEN"},
                  {"bayId": "00000000-0000-0000-0000-000000000011", "bayName": "Bay 2", "status": "CLOSED"}
                ]
                """;
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServer(200, body, callCount);
        try {
            ShopmgrOperationalContextClient client = buildClient(server);

            List<ShopmgrOperationalContextClient.BayAvailabilityDto> bays = client.getBayStatusForLocation(LOCATION_ID);

            assertThat(bays).hasSize(2);
            assertThat(bays.get(0).status()).isEqualTo("OPEN");
            assertThat(bays.get(1).status()).isEqualTo("CLOSED");
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("getBayStatusForLocation returns empty list on 404")
    void getBayStatusForLocation_returnsEmptyList_on404() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServer(404, "[]", callCount);
        try {
            ShopmgrOperationalContextClient client = buildClient(server);

            List<ShopmgrOperationalContextClient.BayAvailabilityDto> bays = client.getBayStatusForLocation(LOCATION_ID);

            assertThat(bays).isEmpty();
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("getBayStatusForLocation rethrows RestClientResponseException on non-404 error")
    void getBayStatusForLocation_rethrowsException_on500() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServer(500, "{}", callCount);
        try {
            ShopmgrOperationalContextClient client = buildClient(server);

            assertThatThrownBy(() -> client.getBayStatusForLocation(LOCATION_ID))
                    .isInstanceOf(RestClientResponseException.class);
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("getBayStatusForLocation returns empty list on connection failure")
    void getBayStatusForLocation_returnsEmptyList_onConnectionFailure() {
        // Port 1 is reserved/not listening — triggers ResourceAccessException
        ShopmgrOperationalContextClient client = new ShopmgrOperationalContextClient(
                RestClient.builder(), "http://localhost:1");

        List<ShopmgrOperationalContextClient.BayAvailabilityDto> bays = client.getBayStatusForLocation(LOCATION_ID);

        assertThat(bays).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ShopmgrOperationalContextClient buildClient(HttpServer server) {
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        return new ShopmgrOperationalContextClient(RestClient.builder(), baseUrl);
    }

    private HttpServer startServer(int statusCode, String body, AtomicInteger callCount)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            callCount.incrementAndGet();
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        return server;
    }
}
