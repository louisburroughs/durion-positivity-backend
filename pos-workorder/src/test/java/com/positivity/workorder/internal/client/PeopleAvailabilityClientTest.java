package com.positivity.workorder.internal.client;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Unit tests for {@link PeopleAvailabilityClient}.
 *
 * <p>
 * Happy-path test uses Mockito deep stubs to avoid Jackson deserialization
 * configuration issues with Lombok {@code @Value @Builder} classes.
 * Error-path tests use an embedded {@link HttpServer} to exercise real
 * RestClient HTTP-level error handling.
 */
class PeopleAvailabilityClientTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TEST_DATE = LocalDate.of(2026, 3, 1);
    private static final String LOCATION_ID = "LOC-001";

    // -----------------------------------------------------------------------
    // Happy path: 200 OK – mock RestClient to bypass Jackson deserialization
    // (PeopleAvailabilityResponse uses Lombok @Value @Builder with a private
    // constructor that cannot be discovered by a bare ObjectMapper).
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fetchAvailability returns populated response on success")
    void fetchAvailability_returnsPopulatedResponse_onSuccess() {
        PeopleAvailabilityResponse expected = PeopleAvailabilityResponse.builder()
                .asOf(Instant.now(TEST_CLOCK))
                .location(LOCATION_ID)
                .people(List.of(
                        PeopleAvailabilityResponse.PersonAvailability.builder()
                                .personId("MECH-001")
                                .firstName("Alice")
                                .lastName("Smith")
                                .currentStatus("AVAILABLE")
                                .build()))
                .build();

        RestClient mockRestClient = mock(RestClient.class, Answers.RETURNS_DEEP_STUBS);
        when(mockRestClient.get()
                .uri(org.mockito.ArgumentMatchers.<Function<org.springframework.web.util.UriBuilder, java.net.URI>>any())
                .retrieve()
                .body(PeopleAvailabilityResponse.class))
                .thenReturn(expected);

        PeopleAvailabilityClient client = new PeopleAvailabilityClient(TEST_CLOCK, mockRestClient);
        var response = client.fetchAvailability(LOCATION_ID, TEST_DATE);

        assertThat(response).isNotNull();
        assertThat(response.getPeople()).hasSize(1);
        assertThat(response.getPeople().get(0).getPersonId()).isEqualTo("MECH-001");
    }

    // -----------------------------------------------------------------------
    // 404 fallback: returns empty people list instead of throwing
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fetchAvailability returns empty list instead of throwing on 404")
    void fetchAvailability_returnsEmptyPeopleList_on404() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServer(404, "{}", callCount);
        try {
            PeopleAvailabilityClient client = buildClient(server);

            var response = client.fetchAvailability(LOCATION_ID, TEST_DATE);

            assertThat(response).isNotNull();
            assertThat(response.getPeople()).isEmpty();
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    // -----------------------------------------------------------------------
    // Non-404 error: wraps exception in RuntimeException with location context
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fetchAvailability wraps non-404 HTTP error in RuntimeException")
    void fetchAvailability_throwsRuntimeException_onServerError() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        HttpServer server = startServer(503, "{}", callCount);
        try {
            PeopleAvailabilityClient client = buildClient(server);

            assertThatThrownBy(() -> client.fetchAvailability(LOCATION_ID, TEST_DATE))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining(LOCATION_ID);
            assertThat(callCount.get()).isGreaterThanOrEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    // -----------------------------------------------------------------------
    // F2: Jackson deserialization works for PeopleAvailabilityResponse
    // (@Jacksonized)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("F2: PeopleAvailabilityResponse deserializes from JSON without error")
    void peopleAvailabilityResponse_deserializesFromJson() throws Exception {
        // This test validates that @Jacksonized is present and Jackson can construct
        // the DTO
        String json = """
                {
                  "asOf": "2026-01-01T08:00:00Z",
                  "location": "LOC-001",
                  "people": [
                    {
                      "personId": "MECH-001",
                      "firstName": "Jane",
                      "lastName": "Smith",
                      "currentStatus": "AVAILABLE",
                      "currentLocationId": "LOC-001",
                      "certifications": ["BRAKE_CERT"],
                      "clock": { "clockInTime": "2026-01-01T08:00:00Z" },
                      "breakInfo": null,
                      "pto": [],
                      "scheduledAvailability": []
                    }
                  ]
                }
                """;
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        PeopleAvailabilityResponse response = mapper.readValue(json, PeopleAvailabilityResponse.class);

        assertThat(response.getPeople()).hasSize(1);
        assertThat(response.getPeople().get(0).getPersonId()).isEqualTo("MECH-001");
        assertThat(response.getPeople().get(0).getCertifications()).containsExactly("BRAKE_CERT");
        assertThat(response.getPeople().get(0).getClock()).isNotNull();
        assertThat(response.getPeople().get(0).getClock().getClockInTime()).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private PeopleAvailabilityClient buildClient(HttpServer server) {
        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .build();
        return new PeopleAvailabilityClient(TEST_CLOCK, restClient);
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
