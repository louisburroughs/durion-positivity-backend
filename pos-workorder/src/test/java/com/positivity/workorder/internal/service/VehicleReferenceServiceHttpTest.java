package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

class VehicleReferenceServiceHttpTest {

    @Test
    void resolve_parsesVehicleInfoAndVin_fromDataEnvelope() throws Exception {
        UUID vehicleId = UUID.randomUUID();
        String payload = """
                {"data":{"vehicleDescription":"2022 Honda Civic EX","vin":"1HGBH41JXMN109186"}}
                """;
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServer("/v1/vehicles/" + vehicleId, 200, payload, callCount);
        try {
            String baseUrl = "http://localhost:" + server.getAddress().getPort();
            VehicleReferenceService service = new VehicleReferenceService(RestClient.builder(), baseUrl);

            VehicleReferenceService.VehicleReference ref = service.resolve(vehicleId);

            assertThat(ref.vehicleInfo()).isEqualTo("2022 Honda Civic EX");
            assertThat(ref.vin()).isEqualTo("1HGBH41JXMN109186");
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolve_returnsFallback_whenRemoteReturns404() throws Exception {
        UUID vehicleId = UUID.randomUUID();
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServer("/v1/vehicles/" + vehicleId, 404, "{}", callCount);
        try {
            String baseUrl = "http://localhost:" + server.getAddress().getPort();
            VehicleReferenceService service = new VehicleReferenceService(RestClient.builder(), baseUrl);

            VehicleReferenceService.VehicleReference ref = service.resolve(vehicleId);

            assertThat(ref.vehicleInfo()).isEqualTo("vehicle-" + vehicleId);
            assertThat(ref.vin()).isNull();
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolveAll_deDuplicatesRequests_forRepeatedIds() throws Exception {
        UUID vehicleId = UUID.randomUUID();
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServer("/v1/vehicles/" + vehicleId, 200,
                "{\"vehicleInfo\":\"2021 Ford F-150\",\"vehicleVin\":\"VIN-123\"}", callCount);
        try {
            String baseUrl = "http://localhost:" + server.getAddress().getPort();
            VehicleReferenceService service = new VehicleReferenceService(RestClient.builder(), baseUrl);

            var resolved = service.resolveAll(List.of(vehicleId, vehicleId));

            assertThat(resolved).hasSize(1);
            assertThat(resolved.get(vehicleId).vehicleInfo()).isEqualTo("2021 Ford F-150");
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
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
