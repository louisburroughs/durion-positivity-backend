package com.positivity.supplier.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.enums.SupplierAuthType;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.service.SecretReferenceResolver;
import com.positivity.supplier.internal.service.SecretSchemeRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * OAuth2 client-credentials token handling (ADR-0050 §4). Protocol-level stubbing uses
 * {@code MockRestServiceServer} per binding arbitration decision 2.
 *
 * <p>What these tests actually protect:
 *
 * <ul>
 *   <li><strong>Cache hit</strong> — a second exchange must not re-hit the token endpoint; vendors
 *       rate-limit it and some invalidate the prior token on reissue.
 *   <li><strong>Skew refresh</strong> — a token expiring inside the skew window must be refreshed
 *       rather than sent, because a token that expires in flight returns a 401 indistinguishable
 *       from a genuine credential failure.
 *   <li><strong>Single-flight</strong> — the scheduler fires many bindings of one profile at once;
 *       exactly one token request may result.
 *   <li><strong>Per-authConfigId keying</strong> — two configs sharing a token URL must not share a
 *       token, or one supplier would authenticate as another.
 * </ul>
 */
class OAuth2ClientCredentialsAuthStrategyTest {

    private static final String TOKEN_URL = "https://vendor.test/oauth/token";
    private static final String CLIENT_ID = "client-id-value";
    private static final String CLIENT_SECRET = "client-secret-value";

    private static final UUID CONFIG_A = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
    private static final UUID CONFIG_B = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02");

    private final SecretSchemeRegistry registry = new SecretSchemeRegistry(List.of(new MapSecretResolver(Map.of(
            "TOKEN_URL", TOKEN_URL,
            "CLIENT_ID", CLIENT_ID,
            "CLIENT_SECRET", CLIENT_SECRET))));

    @Test
    void mintsATokenAndSendsItAsBearer() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(tokenJson("tok-1", 3600), MediaType.APPLICATION_JSON));

        HttpHeaders headers = new HttpHeaders();
        strategy(builder, Clock.systemUTC(), 30).apply(headers, config(CONFIG_A));

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer tok-1");
        server.verify();
    }

    @Test
    void sendsClientCredentialsInTheBasicHeaderPerRfc6749() {
        String expectedBasic = "Basic "
                + Base64.getEncoder()
                        .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, expectedBasic))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE))
                .andRespond(withSuccess(tokenJson("tok-1", 3600), MediaType.APPLICATION_JSON));

        strategy(builder, Clock.systemUTC(), 30).apply(new HttpHeaders(), config(CONFIG_A));

        server.verify();
    }

    @Test
    void cachesTheTokenSoASecondExchangeDoesNotReHitTheTokenEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // ExpectedCount.once() is the assertion: a second request fails the test.
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess(tokenJson("tok-1", 3600), MediaType.APPLICATION_JSON));

        OAuth2ClientCredentialsAuthStrategy strategy = strategy(builder, Clock.systemUTC(), 30);
        HttpHeaders first = new HttpHeaders();
        HttpHeaders second = new HttpHeaders();
        strategy.apply(first, config(CONFIG_A));
        strategy.apply(second, config(CONFIG_A));

        assertThat(first.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer tok-1");
        assertThat(second.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer tok-1");
        server.verify();
    }

    @Test
    void refreshesATokenExpiringInsideTheSkewWindowInsteadOfSendingIt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.twice(), requestTo(TOKEN_URL))
                .andRespond(withSuccess(tokenJson("tok-short", 60), MediaType.APPLICATION_JSON));

        Instant start = Instant.parse("2026-08-11T12:00:00Z");
        MutableClock clock = new MutableClock(start);
        // 60s lifetime, 30s skew: at t+31s the token is inside the skew window.
        OAuth2ClientCredentialsAuthStrategy strategy = strategy(builder, clock, 30);

        strategy.apply(new HttpHeaders(), config(CONFIG_A));
        clock.advance(Duration.ofSeconds(31));
        strategy.apply(new HttpHeaders(), config(CONFIG_A));

        server.verify();
    }

    @Test
    void keepsUsingATokenStillOutsideTheSkewWindow() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess(tokenJson("tok-long", 3600), MediaType.APPLICATION_JSON));

        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T12:00:00Z"));
        OAuth2ClientCredentialsAuthStrategy strategy = strategy(builder, clock, 30);

        strategy.apply(new HttpHeaders(), config(CONFIG_A));
        clock.advance(Duration.ofSeconds(600));
        strategy.apply(new HttpHeaders(), config(CONFIG_A));

        server.verify();
    }

    @Test
    void tokensAreKeyedPerAuthConfigIdNotPerTokenUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Same URL, two auth configs: two distinct tokens, or one supplier authenticates as another.
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess(tokenJson("tok-a", 3600), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess(tokenJson("tok-b", 3600), MediaType.APPLICATION_JSON));

        OAuth2ClientCredentialsAuthStrategy strategy = strategy(builder, Clock.systemUTC(), 30);
        HttpHeaders a = new HttpHeaders();
        HttpHeaders b = new HttpHeaders();
        strategy.apply(a, config(CONFIG_A));
        strategy.apply(b, config(CONFIG_B));

        assertThat(a.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer tok-a");
        assertThat(b.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer tok-b");
        server.verify();
    }

    @Test
    void concurrentCallersForOneAuthConfigMintExactlyOneToken() throws Exception {
        int threads = 16;
        AtomicInteger tokenRequests = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);

        // A counting, deliberately slow resolver stands in for the token endpoint: it counts how
        // many times the refresh path was actually entered. Without single-flight this is ~16.
        SecretSchemeRegistry countingRegistry = new SecretSchemeRegistry(List.of(new MapSecretResolver(
                Map.of("TOKEN_URL", TOKEN_URL, "CLIENT_ID", CLIENT_ID, "CLIENT_SECRET", CLIENT_SECRET))));

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL)).andRespond(request -> {
            tokenRequests.incrementAndGet();
            // Hold the single in-flight refresh open until every thread has arrived, so any
            // missing mutual exclusion would show up as a second request.
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return withSuccess(tokenJson("tok-single", 3600), MediaType.APPLICATION_JSON)
                    .createResponse(request);
        });

        OAuth2ClientCredentialsAuthStrategy strategy = new OAuth2ClientCredentialsAuthStrategy(
                countingRegistry, transportOf(builder), Clock.systemUTC(), 30, 5000, 15000);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    HttpHeaders headers = new HttpHeaders();
                    strategy.apply(headers, config(CONFIG_A));
                    return headers.getFirst(HttpHeaders.AUTHORIZATION);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            Thread.sleep(150); // let contenders pile up on the refresh lock
            release.countDown();

            for (java.util.concurrent.Future<String> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo("Bearer tok-single");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(tokenRequests.get())
                .as("single-flight: concurrent callers for one authConfigId must cause exactly one"
                        + " token request, or the vendor rate-limits us and may invalidate the prior token")
                .isEqualTo(1);
        server.verify();
    }

    /**
     * A 5xx from the token endpoint is a TRANSPORT failure of the credential leg, not a statement
     * that the operator's configuration is wrong. This test previously asserted
     * {@code AUTH_CONFIG_MISSING}, which pinned the defect: nothing reached the vendor's business
     * endpoint, so ADR-0052 §5 makes it pre-send and retryable, and reporting it as a configuration
     * error sent operators hunting for an env var that was present and correct.
     */
    @Test
    void aFailingTokenEndpointIsATransportFailureNotAConfigurationError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> strategy(builder, Clock.systemUTC(), 30).apply(new HttpHeaders(), config(CONFIG_A)))
                .isInstanceOf(SupplierAuthTransportException.class)
                .hasMessageNotContaining(CLIENT_SECRET)
                .hasMessageNotContaining(CLIENT_ID);
    }

    @Test
    void aRejectedClientSecretStaysAConfigurationErrorSoWeDoNotHammerTheVendor() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> strategy(builder, Clock.systemUTC(), 30).apply(new HttpHeaders(), config(CONFIG_A)))
                .isInstanceOf(SupplierConfigurationException.class)
                .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.AUTH_CREDENTIALS_REJECTED)
                .hasMessageNotContaining(CLIENT_SECRET);
    }

    @Test
    void aTransportFailureRetainsItsCauseSoOperatorsCanTellDnsFromTls() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> strategy(builder, Clock.systemUTC(), 30).apply(new HttpHeaders(), config(CONFIG_A)))
                .isInstanceOf(SupplierAuthTransportException.class)
                .hasCauseInstanceOf(org.springframework.web.client.RestClientException.class);
    }

    @Test
    void aTokenResponseWithoutAnAccessTokenFailsTyped() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> strategy(builder, Clock.systemUTC(), 30).apply(new HttpHeaders(), config(CONFIG_A)))
                .isInstanceOf(SupplierConfigurationException.class)
                .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.AUTH_TOKEN_RESPONSE_INVALID)
                .hasMessageContaining("supplied no access_token");
    }

    @Test
    void invalidateForcesTheNextExchangeToRefresh() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.twice(), requestTo(TOKEN_URL))
                .andRespond(withSuccess(tokenJson("tok-1", 3600), MediaType.APPLICATION_JSON));

        OAuth2ClientCredentialsAuthStrategy strategy = strategy(builder, Clock.systemUTC(), 30);
        strategy.apply(new HttpHeaders(), config(CONFIG_A));
        strategy.invalidateCachedCredential(CONFIG_A);
        strategy.apply(new HttpHeaders(), config(CONFIG_A));

        server.verify();
    }

    @Test
    void aTransientAuthConfigWithNoIdentityIsRejectedRatherThanMisCached() {
        SupplierAuthConfigEntity transientConfig = config(CONFIG_A);
        transientConfig.setId(null);

        assertThatThrownBy(() ->
                        strategy(RestClient.builder(), Clock.systemUTC(), 30).apply(new HttpHeaders(), transientConfig))
                .isInstanceOf(SupplierConfigurationException.class)
                .hasMessageContaining("no identity yet");
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────

    /**
     * Wraps a {@code MockRestServiceServer}-bound builder as the production transport source.
     *
     * <p>A stub subclass rather than a test-only constructor on the strategy: the seam already exists as a
     * public, non-final method, and adding a second constructor purely for tests would let production code
     * bypass the shared vendor transport by accident. Timeout arguments are ignored here because what these
     * tests exercise is the OAuth2 protocol, not the socket.
     */
    private static SupplierHttpClients transportOf(RestClient.Builder builder) {
        RestClient bound = builder.build();
        return new SupplierHttpClients() {
            @Override
            public RestClient forTimeouts(int connectTimeoutMs, int readTimeoutMs) {
                return bound;
            }
        };
    }

    private OAuth2ClientCredentialsAuthStrategy strategy(RestClient.Builder builder, Clock clock, long skewSeconds) {
        return new OAuth2ClientCredentialsAuthStrategy(registry, transportOf(builder), clock, skewSeconds, 5000, 15000);
    }

    private static String tokenJson(String token, long expiresIn) {
        return "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + "}";
    }

    private static SupplierAuthConfigEntity config(UUID id) {
        SupplierAuthConfigEntity config = new SupplierAuthConfigEntity();
        config.setId(id);
        config.setName("vendor-oauth-" + id);
        config.setType(SupplierAuthType.OAUTH2_CLIENT_CREDENTIALS);
        config.setTokenUrlRef("env:TOKEN_URL");
        config.setClientIdRef("env:CLIENT_ID");
        config.setClientSecretRef("env:CLIENT_SECRET");
        return config;
    }

    /** Fixed-map resolver claiming {@code env}; the real one's test seam is package-private. */
    private record MapSecretResolver(Map<String, String> values) implements SecretReferenceResolver {

        @Override
        @NonNull
        public String scheme() {
            return "env";
        }

        @Override
        @NonNull
        public String resolve(@NonNull String reference) {
            String key = reference.substring(reference.indexOf(':') + 1);
            String value = values.get(key);
            if (value == null) {
                throw new SupplierConfigurationException(
                        SupplierConfigurationException.SECRET_NOT_FOUND,
                        "Secret reference '" + reference + "' resolves to no value");
            }
            return value;
        }
    }

    /** Test clock that can be advanced to drive token expiry deterministically. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
