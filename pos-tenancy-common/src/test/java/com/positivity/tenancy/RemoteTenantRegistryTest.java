package com.positivity.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RemoteTenantRegistryTest {

    private static final String URL = "http://tenant/internal/v1/tenants";
    private static final UUID STATIC_TENANT = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID ACME = UUID.fromString("01990000-0000-7000-8000-000000000a01");
    private static final UUID BOLT = UUID.fromString("01990000-0000-7000-8000-000000000b02");
    private static final UUID HELD = UUID.fromString("01990000-0000-7000-8000-000000000c03");

    private static final String TWO_ACTIVE = """
            [{"tenantId":"%s","slug":"acme","displayName":"Acme","status":"ACTIVE"},
             {"tenantId":"%s","slug":"bolt","displayName":null,"status":"ACTIVE"},
             {"tenantId":"%s","slug":"held","displayName":"Held","status":"SUSPENDED"}]
            """.formatted(ACME, BOLT, HELD);

    /** A clock the test moves by hand; the registry throttles on it. */
    private static final class SteppingClock extends Clock {
        private Instant now = Instant.parse("2026-09-10T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final SteppingClock clock = new SteppingClock();
    private MockRestServiceServer server;
    private RemoteTenantRegistry registry;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        TenancyProperties properties = new TenancyProperties();
        properties.setDefaultTenantId(STATIC_TENANT);
        properties.getRegistry().setMode(TenancyProperties.Registry.Mode.REMOTE);
        properties.getRegistry().setUrl(URL);
        properties.getRegistry().setSecret("registry-secret");
        properties.getRegistry().setRefresh(Duration.ofSeconds(60));
        registry = new RemoteTenantRegistry(properties, builder.build(), clock);
    }

    private void expectSuccess(String body) {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(RemoteTenantRegistry.SECRET_HEADER, "registry-secret"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("the snapshot starts as the static list and the first read fetches the remote one")
    void startsStaticThenFetches() {
        assertThat(registry.snapshotSize()).isEqualTo(1);
        assertThat(registry.lastSuccessEpochSeconds()).isZero();
        expectSuccess(TWO_ACTIVE);

        List<UUID> active = registry.activeTenantIds();

        assertThat(active).containsExactly(ACME, BOLT);
        assertThat(registry.lastSuccessEpochSeconds()).isEqualTo(clock.instant().getEpochSecond());
        assertThat(registry.consecutiveFailures()).isZero();
        server.verify();
    }

    @Test
    void sendsTheSharedSecretHeader() {
        server.expect(requestTo(URL))
                .andExpect(header(RemoteTenantRegistry.SECRET_HEADER, "registry-secret"))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess(TWO_ACTIVE, MediaType.APPLICATION_JSON));

        registry.activeTenantIds();

        server.verify();
    }

    @Test
    @DisplayName("reads inside the refresh interval return the snapshot without a fetch")
    void refreshIsThrottled() {
        // MockRestServiceServer takes every expectation up front and serves them in order.
        expectSuccess(TWO_ACTIVE);
        expectSuccess("[{\"tenantId\":\"%s\",\"slug\":\"acme\",\"status\":\"ACTIVE\"}]".formatted(ACME));

        registry.activeTenantIds();
        clock.advance(Duration.ofSeconds(59));
        assertThat(registry.activeTenantIds()).containsExactly(ACME, BOLT);
        assertThat(registry.consecutiveFailures()).isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(registry.activeTenantIds()).containsExactly(ACME);
        server.verify();
    }

    @Test
    @DisplayName("a transport failure keeps the last good snapshot and counts; a later success recovers")
    void failureKeepsLastGoodAndRecovers() {
        expectSuccess(TWO_ACTIVE);
        server.expect(requestTo(URL)).andRespond(withException(new IOException("connection refused")));
        server.expect(requestTo(URL)).andRespond(withException(new IOException("still down")));
        expectSuccess("[{\"tenantId\":\"%s\",\"slug\":\"bolt\",\"status\":\"ACTIVE\"}]".formatted(BOLT));

        registry.activeTenantIds();
        long firstSuccess = registry.lastSuccessEpochSeconds();

        clock.advance(Duration.ofMinutes(1));
        assertThat(registry.activeTenantIds()).containsExactly(ACME, BOLT);
        assertThat(registry.consecutiveFailures()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));
        assertThat(registry.activeTenantIds()).containsExactly(ACME, BOLT);
        assertThat(registry.consecutiveFailures()).isEqualTo(2);
        assertThat(registry.lastSuccessEpochSeconds()).isEqualTo(firstSuccess);

        clock.advance(Duration.ofMinutes(1));
        assertThat(registry.activeTenantIds()).containsExactly(BOLT);
        assertThat(registry.consecutiveFailures()).isZero();
        assertThat(registry.lastSuccessEpochSeconds()).isGreaterThan(firstSuccess);
        server.verify();
    }

    @Test
    @DisplayName("a failed first fetch leaves the static snapshot in place")
    void failedFirstFetchKeepsTheStaticList() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(registry.activeTenantIds()).containsExactly(STATIC_TENANT);
        assertThat(registry.consecutiveFailures()).isEqualTo(1);
        assertThat(registry.lastSuccessEpochSeconds()).isZero();
    }

    @Test
    @DisplayName("the snapshot is incomplete on the static seed and while a refresh is failing")
    void hasCompleteSnapshotTracksTheFetchState() {
        expectSuccess(TWO_ACTIVE);
        server.expect(requestTo(URL)).andRespond(withException(new IOException("connection refused")));
        expectSuccess(TWO_ACTIVE);

        assertThat(registry.hasCompleteSnapshot())
                .as("the static seed a remote registry starts on is not the fleet")
                .isFalse();

        registry.activeTenantIds();
        assertThat(registry.hasCompleteSnapshot()).isTrue();

        clock.advance(Duration.ofMinutes(1));
        registry.activeTenantIds();
        assertThat(registry.hasCompleteSnapshot())
                .as("a snapshot kept through an outage cannot hold a tenant created since")
                .isFalse();

        clock.advance(Duration.ofMinutes(1));
        registry.activeTenantIds();
        assertThat(registry.hasCompleteSnapshot()).isTrue();
        server.verify();
    }

    @Test
    void non2xxIsAFailure() {
        expectSuccess(TWO_ACTIVE);
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        registry.activeTenantIds();
        clock.advance(Duration.ofMinutes(1));

        assertThat(registry.activeTenantIds()).containsExactly(ACME, BOLT);
        assertThat(registry.consecutiveFailures()).isEqualTo(1);
        server.verify();
    }

    @Test
    @DisplayName("a 3xx carrying a valid JSON body is a failure too: only 2xx replaces the snapshot")
    void redirectWithAValidBodyIsAFailure() {
        expectSuccess(TWO_ACTIVE);
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .body("[{\"tenantId\":\"" + ACME + "\",\"status\":\"ACTIVE\"}]")
                        .contentType(MediaType.APPLICATION_JSON));

        registry.activeTenantIds();
        clock.advance(Duration.ofMinutes(1));

        assertThat(registry.activeTenantIds()).containsExactly(ACME, BOLT);
        assertThat(registry.consecutiveFailures()).isEqualTo(1);
        server.verify();
    }

    @Test
    @DisplayName("the static seed drops the platform tenant too, so a failed first fetch never yields it")
    void platformTenantIsExcludedFromTheStaticSeed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer local = MockRestServiceServer.bindTo(builder).build();
        TenancyProperties properties = new TenancyProperties();
        // pos-tenant configures the platform tenant as its default, and any module may list it.
        properties.setDefaultTenantId(PlatformTenant.ID);
        properties.setTenants(List.of(PlatformTenant.ID, ACME));
        properties.getRegistry().setMode(TenancyProperties.Registry.Mode.REMOTE);
        properties.getRegistry().setUrl(URL);
        RemoteTenantRegistry seeded = new RemoteTenantRegistry(properties, builder.build(), clock);

        assertThat(seeded.snapshotSize()).isEqualTo(1);
        local.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(seeded.activeTenantIds()).containsExactly(ACME);
        assertThat(seeded.consecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("an additive field in a newer pos-tenant response does not fail the fetch")
    void unknownFieldsAreIgnored() {
        expectSuccess("[{\"tenantId\":\"" + ACME + "\",\"slug\":\"acme\",\"displayName\":\"Acme\","
                + "\"status\":\"ACTIVE\",\"region\":\"us-east-1\",\"createdAt\":\"2026-09-11T00:00:00Z\"}]");

        assertThat(registry.activeTenantIds()).containsExactly(ACME);
        assertThat(registry.consecutiveFailures()).isZero();
        server.verify();
    }

    @Test
    @DisplayName("the platform tenant is never part of the snapshot, ACTIVE or not")
    void platformTenantIsExcluded() {
        expectSuccess("[{\"tenantId\":\"" + PlatformTenant.ID + "\",\"slug\":\"platform\",\"status\":\"ACTIVE\"},"
                + "{\"tenantId\":\"" + ACME + "\",\"slug\":\"acme\",\"status\":\"ACTIVE\"}]");

        assertThat(registry.activeTenantIds()).containsExactly(ACME);
        server.verify();
    }

    @Test
    @DisplayName("a slow refresh on one thread never blocks another reader, which keeps the old snapshot")
    void aSlowRefreshNeverBlocksOtherReaders() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger hits = new AtomicInteger();
        httpServer.createContext("/internal/v1/tenants", exchange -> {
            hits.incrementAndGet();
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = TWO_ACTIVE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        httpServer.start();
        try {
            TenancyProperties properties = new TenancyProperties();
            properties.setDefaultTenantId(STATIC_TENANT);
            properties.getRegistry().setMode(TenancyProperties.Registry.Mode.REMOTE);
            properties
                    .getRegistry()
                    .setUrl("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/internal/v1/tenants");
            properties.getRegistry().setSecret("registry-secret");
            RemoteTenantRegistry slow =
                    new RemoteTenantRegistry(properties, RestClient.builder().build(), clock);

            Thread refresher = new Thread(slow::activeTenantIds, "slow-refresh");
            refresher.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            List<UUID> whileRefreshing = slow.activeTenantIds();
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            assertThat(whileRefreshing).containsExactly(STATIC_TENANT);
            assertThat(waited).isLessThan(Duration.ofSeconds(2));
            assertThat(hits.get())
                    .as("the second reader issued no request of its own")
                    .isEqualTo(1);

            release.countDown();
            refresher.join(Duration.ofSeconds(5).toMillis());
            assertThat(slow.activeTenantIds()).containsExactly(ACME, BOLT);
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    @DisplayName("an empty list, or one with no ACTIVE tenant, is a failure and never an empty snapshot")
    void emptyOrInactiveListIsAFailure() {
        expectSuccess(TWO_ACTIVE);
        expectSuccess("[]");
        expectSuccess("[{\"tenantId\":\"%s\",\"slug\":\"held\",\"status\":\"SUSPENDED\"}]".formatted(HELD));

        registry.activeTenantIds();

        clock.advance(Duration.ofMinutes(1));
        assertThat(registry.activeTenantIds()).containsExactly(ACME, BOLT);
        assertThat(registry.consecutiveFailures()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));
        assertThat(registry.activeTenantIds()).containsExactly(ACME, BOLT);
        assertThat(registry.consecutiveFailures()).isEqualTo(2);
        server.verify();
    }

    @Test
    void malformedBodyIsAFailure() {
        expectSuccess("not json");

        assertThat(registry.activeTenantIds()).containsExactly(STATIC_TENANT);
        assertThat(registry.consecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("no static list and no default tenant: an empty snapshot until the first fetch succeeds")
    void emptyStaticSnapshotWhenNothingIsConfigured() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer local = MockRestServiceServer.bindTo(builder).build();
        TenancyProperties properties = new TenancyProperties();
        properties.getRegistry().setUrl(URL);
        RemoteTenantRegistry bare = new RemoteTenantRegistry(properties, builder.build(), clock);
        assertThat(bare.snapshotSize()).isZero();
        local.expect(requestTo(URL))
                .andExpect(header(RemoteTenantRegistry.SECRET_HEADER, ""))
                .andRespond(withSuccess(TWO_ACTIVE, MediaType.APPLICATION_JSON));

        assertThat(bare.activeTenantIds()).containsExactly(ACME, BOLT);
    }

    @Test
    void explicitTenantListSeedsTheSnapshotAheadOfTheDefault() {
        TenancyProperties properties = new TenancyProperties();
        properties.setDefaultTenantId(STATIC_TENANT);
        properties.setTenants(List.of(ACME, BOLT));
        RemoteTenantRegistry seeded =
                new RemoteTenantRegistry(properties, RestClient.builder().build(), clock);

        assertThat(seeded.snapshotSize()).isEqualTo(2);
    }
}
