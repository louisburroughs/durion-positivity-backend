package com.positivity.accounting.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.accounting.internal.dto.ChangeTaxRegistrationRequest;
import com.positivity.accounting.internal.dto.RecordTaxRegistrationRequest;
import com.positivity.accounting.internal.exception.TaxRegistrationRelayException;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP:550 S32c AC 2 and AC 4 at the wire: the front door forwards the secret, the actor and the tenant; relays
 * pos-tax's 400, 404 and 409 unchanged; answers 503 for anything else; and never logs the number.
 */
@DisplayName("TaxRegistrationClient — the accounting front door's call to pos-tax (CAP:550 S32c)")
class TaxRegistrationClientTest {

    private static final String BASE = "http://pos-tax:8091";
    private static final String SECRET = "test-only-accounting-front-door-secret";
    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-0000000000f1");
    private static final String ACTOR = "01990000-0000-7000-8000-0000000000e1";
    private static final String MALFORMED = "12345678 RT 0001";

    private static final String REGISTRATION = """
            {"registrationId":"01990000-0000-7000-8000-0000000000a1","countryCode":"CA","regime":"GST_HST",
             "registrationNumber":"123456789RT0001","jurisdictionCode":"CA","effectiveFrom":"2026-01-01",
             "effectiveTo":null,"status":"ACTIVE","version":0,"createdAt":"2026-10-08T12:00:00Z",
             "createdBy":"01990000-0000-7000-8000-0000000000e1","updatedAt":"2026-10-08T12:00:00Z",
             "updatedBy":"01990000-0000-7000-8000-0000000000e1"}
            """;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private MockRestServiceServer server;
    private TaxRegistrationClient client;

    private TaxRegistrationClient client(String secret) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new TaxRegistrationClient(builder, new ObjectMapper(), BASE, secret, meters);
    }

    @BeforeEach
    void setUp() {
        client = client(SECRET);
        TenantContext.bind(TENANT);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static RecordTaxRegistrationRequest record(String number) {
        return new RecordTaxRegistrationRequest(
                "CA",
                "GST_HST",
                number,
                LocalDate.of(2026, 1, 1),
                null,
                "Registered with the tax authority",
                UUID.fromString("01990000-0000-7000-8000-000000000201"));
    }

    @Test
    @DisplayName("forwards the secret, the actor and the tenant, and reads pos-tax's registration")
    void forwardsTheFrontDoorContext() {
        server.expect(requestTo(BASE + "/v1/tax/registrations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(TaxRegistrationClient.SECRET_HEADER, SECRET))
                .andExpect(header(TaxRegistrationClient.ACTOR_HEADER, ACTOR))
                .andExpect(header("X-Tenant-Id", TENANT.toString()))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"regime\":\"GST_HST\"")))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(REGISTRATION));

        TaxRegistrationClient.Written written = client.create(record("123456789 RT 0001"), ACTOR);

        assertThat(written.replayed()).isFalse();
        assertThat(written.registration().registrationNumber()).isEqualTo("123456789RT0001");
        server.verify();
    }

    @Test
    @DisplayName("a 200 is pos-tax's answer to a replayed request id")
    void replay() {
        server.expect(requestTo(BASE + "/v1/tax/registrations/01990000-0000-7000-8000-0000000000a1"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess(REGISTRATION, MediaType.APPLICATION_JSON));

        TaxRegistrationClient.Written written = client.update(
                UUID.fromString("01990000-0000-7000-8000-0000000000a1"),
                new ChangeTaxRegistrationRequest(
                        "123456789RT0001",
                        LocalDate.of(2026, 1, 1),
                        null,
                        0L,
                        "Corrected the registration date",
                        UUID.randomUUID()),
                ACTOR);

        assertThat(written.replayed()).isTrue();
    }

    @Test
    @DisplayName("AC 1: pos-tax's 409 TAX_REGISTRATION_OVERLAP is relayed unchanged")
    void relaysTheOverlap() {
        server.expect(requestTo(BASE + "/v1/tax/registrations"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"TAX_REGISTRATION_OVERLAP","message":"Another registration is in effect",
                                 "status":409,"timestamp":"2026-10-08T12:00:00Z","correlationId":"corr-1"}
                                """));

        assertThatThrownBy(() -> client.create(record("987654321RT0001"), ACTOR))
                .isInstanceOfSatisfying(TaxRegistrationRelayException.class, relayed -> {
                    assertThat(relayed.getStatus()).isEqualTo(409);
                    assertThat(relayed.getError().code()).isEqualTo("TAX_REGISTRATION_OVERLAP");
                    assertThat(relayed.getError().correlationId()).isEqualTo("corr-1");
                });
    }

    @Test
    @DisplayName("AC 2: pos-tax's 400 on the number is relayed, and the number is in no log line at DEBUG")
    void relaysTheShapeRefusalWithoutLoggingTheNumber() {
        server.expect(requestTo(BASE + "/v1/tax/registrations"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"VALIDATION_ERROR","message":"Request validation failed","status":400,
                                 "timestamp":"2026-10-08T12:00:00Z","correlationId":"corr-2",
                                 "fieldErrors":[{"field":"registrationNumber",
                                   "message":"registrationNumber does not match the regime's registration-number shape"}]}
                                """));
        Logger root = (Logger) LoggerFactory.getLogger("com.positivity");
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        root.setLevel(Level.DEBUG);
        try {
            assertThatThrownBy(() -> client.create(record(MALFORMED), ACTOR))
                    .isInstanceOfSatisfying(TaxRegistrationRelayException.class, relayed -> {
                        assertThat(relayed.getStatus()).isEqualTo(400);
                        assertThat(relayed.getError().fieldErrors())
                                .singleElement()
                                .satisfies(error -> assertThat(error.field()).isEqualTo("registrationNumber"));
                        assertThat(relayed.getMessage()).doesNotContain(MALFORMED);
                    });
        } finally {
            root.detachAppender(logs);
            root.setLevel(before);
        }
        assertThat(logs.list)
                .allSatisfy(event -> assertThat(event.getFormattedMessage())
                        .doesNotContain(MALFORMED)
                        .doesNotContain("12345678RT0001"));
    }

    @Test
    @DisplayName("AC 4: pos-tax unreachable, failing or refusing this secret is 503")
    void unavailable() {
        server.expect(requestTo(BASE + "/v1/tax/registrations")).andRespond(request -> {
            throw new IOException("Connection refused");
        });
        assertThatThrownBy(() -> client.create(record("123456789RT0001"), ACTOR))
                .isInstanceOf(TaxServiceUnavailableException.class);

        client = client(SECRET);
        server.expect(requestTo(BASE + "/v1/tax/registrations")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.create(record("123456789RT0001"), ACTOR))
                .isInstanceOf(TaxServiceUnavailableException.class);
        assertThat(meters.counter(TaxRegistrationClient.SECRET_REFUSED_COUNTER).count())
                .as("a secret mismatch is counted")
                .isEqualTo(1.0);

        client = client(SECRET);
        server.expect(requestTo(BASE + "/v1/tax/registrations"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> client.create(record("123456789RT0001"), ACTOR))
                .isInstanceOf(TaxServiceUnavailableException.class);
    }

    @Test
    @DisplayName("AC 4: a pos-tax that accepts the connection but never answers is 503 after the read timeout")
    void readTimeoutIsUnavailable() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            TaxRegistrationClient timed = new TaxRegistrationClient(
                    RestClient.builder(),
                    new ObjectMapper(),
                    "http://localhost:" + silent.getLocalPort(),
                    SECRET,
                    Duration.ofSeconds(2),
                    Duration.ofMillis(300),
                    new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
            long started = System.nanoTime();

            assertThatThrownBy(() -> timed.create(record("123456789RT0001"), ACTOR))
                    .isInstanceOf(TaxServiceUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("ADR-0017 §4: the inbound X-Correlation-Id is forwarded to pos-tax")
    void forwardsTheCorrelationId() {
        MockHttpServletRequest inbound = new MockHttpServletRequest();
        inbound.addHeader("X-Correlation-Id", "corr-inbound");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(inbound));
        try {
            server.expect(requestTo(BASE + "/v1/tax/registrations"))
                    .andExpect(header("X-Correlation-Id", "corr-inbound"))
                    .andRespond(withStatus(HttpStatus.CREATED)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(REGISTRATION));

            client.create(record("123456789RT0001"), ACTOR);

            server.verify();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("pos-tax's 422 TAX_REGIME_NOT_DECLARED is relayed with its code")
    void relaysTheConfigurationRefusal() {
        server.expect(requestTo(BASE + "/v1/tax/registrations"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"TAX_REGIME_NOT_DECLARED","message":"Request refused by the tax configuration",
                                 "status":422,"timestamp":"2026-10-08T12:00:00Z","correlationId":"corr-3"}
                                """));

        assertThatThrownBy(() -> client.create(record("123456789RT0001"), ACTOR))
                .isInstanceOfSatisfying(TaxRegistrationRelayException.class, relayed -> {
                    assertThat(relayed.getStatus()).isEqualTo(422);
                    assertThat(relayed.getError().code()).isEqualTo("TAX_REGIME_NOT_DECLARED");
                });
    }

    @Test
    @DisplayName("a blank secret here answers 503 without calling pos-tax")
    void blankSecretNeverCalls() {
        TaxRegistrationClient unconfigured = client(" ");

        assertThatThrownBy(() -> unconfigured.create(record("123456789RT0001"), ACTOR))
                .isInstanceOf(TaxServiceUnavailableException.class);
        server.verify();
    }
}
