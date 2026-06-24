package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.internal.service.VehicleReferenceService;
import com.positivity.workorder.internal.service.VehicleReferenceService.VehicleKey;
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

class VehicleReferenceServiceHttpTest {

    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID VEHICLE = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void resolve_buildsLabelFromYearMakeModel_andVin() throws Exception {
        String payload = "{\"vehicleId\":\"" + VEHICLE
                + "\",\"make\":\"Honda\",\"model\":\"Civic\",\"year\":2022,\"vin\":\"1HGBH41JXMN109186\"}";
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServer("/v1/crm/" + CUSTOMER + "/vehicles/" + VEHICLE, 200, payload, callCount);
        try {
            VehicleReferenceService service = newService(server);

            VehicleReferenceService.VehicleReference ref = service.resolve(CUSTOMER, VEHICLE);

            assertThat(ref.vehicleInfo()).isEqualTo("2022 Honda Civic");
            assertThat(ref.vin()).isEqualTo("1HGBH41JXMN109186");
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolve_returnsFallback_whenRemoteReturns404() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServer("/v1/crm/" + CUSTOMER + "/vehicles/" + VEHICLE, 404, "{}", callCount);
        try {
            VehicleReferenceService service = newService(server);

            VehicleReferenceService.VehicleReference ref = service.resolve(CUSTOMER, VEHICLE);

            assertThat(ref.vehicleInfo()).isEqualTo("vehicle-" + VEHICLE);
            assertThat(ref.vin()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolve_returnsFallback_whenCustomerUnknown() {
        VehicleReferenceService service = new VehicleReferenceService(RestClient.builder(), "localhost:1");
        VehicleReferenceService.VehicleReference ref = service.resolve(null, VEHICLE);
        assertThat(ref.vehicleInfo()).isEqualTo("vehicle-" + VEHICLE);
    }

    @Test
    void resolveAll_listsPerCustomerOnce_andIndexesByVehicleId() throws Exception {
        UUID v2 = UUID.fromString("00000000-0000-0000-0000-000000000003");
        String listPayload = "[{\"vehicleId\":\"" + VEHICLE
                + "\",\"make\":\"Toyota\",\"model\":\"Tacoma\",\"year\":2014,\"vin\":\"VIN-A\"},"
                + "{\"vehicleId\":\"" + v2
                + "\",\"make\":\"Ford\",\"model\":\"F-150\",\"year\":2019,\"vin\":\"VIN-B\"}]";
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServer("/v1/crm/" + CUSTOMER + "/vehicles", 200, listPayload, callCount);
        try {
            VehicleReferenceService service = newService(server);

            var resolved = service.resolveAll(List.of(new VehicleKey(CUSTOMER, VEHICLE), new VehicleKey(CUSTOMER, v2)));

            assertThat(resolved).hasSize(2);
            assertThat(resolved.get(VEHICLE).vehicleInfo()).isEqualTo("2014 Toyota Tacoma");
            assertThat(resolved.get(VEHICLE).vin()).isEqualTo("VIN-A");
            assertThat(resolved.get(v2).vehicleInfo()).isEqualTo("2019 Ford F-150");
            // One list call for the shared customer.
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    private VehicleReferenceService newService(HttpServer server) {
        String serviceId = "localhost:" + server.getAddress().getPort();
        return new VehicleReferenceService(RestClient.builder(), serviceId);
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
