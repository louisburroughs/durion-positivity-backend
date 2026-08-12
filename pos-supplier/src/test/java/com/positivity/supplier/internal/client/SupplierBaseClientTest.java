package com.positivity.supplier.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.RetryBackoff;
import com.positivity.supplier.internal.enums.SupplierAuthType;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.service.SecretReferenceResolver;
import com.positivity.supplier.internal.service.SecretSchemeRegistry;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.internal.spi.ExchangeContext;
import com.positivity.supplier.internal.spi.ExchangeObserver;
import com.positivity.supplier.internal.spi.ExchangeOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.ResourceAccessException;

/**
 * Transport behaviour of {@link SupplierBaseClient}, exercised against a real socket via
 * {@link FaultInjectingHttpServer} (binding arbitration decision 2) because the questions that matter
 * are socket-level: did the request reach the wire, and may it therefore be retried.
 *
 * <p>The retry assertions are the ones with teeth. ADR-0052 §5 permits an automatic retry only when
 * the failure demonstrably preceded transmission; retrying a read timeout on an order would duplicate
 * it at the vendor. Every retry test therefore asserts on
 * {@link FaultInjectingHttpServer#receivedRequests()} — how many requests actually hit the wire —
 * rather than only on the returned outcome, because an outcome can look right while the client has
 * silently re-sent the document.
 */
class SupplierBaseClientTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
    private static final UUID BINDING_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60");
    private static final UUID AUTH_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a61");

    /**
     * Read budget for every test whose subject is not a timeout.
     *
     * <p>It was 400ms for all of them, borrowed from the one test that must actually reach a read
     * timeout — and that made unrelated assertions depend on latency. A test building its own client
     * pays JDK {@code HttpClient} cold-start inside the budget rather than inheriting a warm one, and
     * with {@code junit-platform.properties} running classes two-at-a-time on a shared CI runner, 400ms
     * is not a safe margin: {@code anObserverThatThrowsDoesNotFailASuccessfulExchange} failed in CI with
     * {@code POST_SEND_AMBIGUOUS} — a genuine read timeout — while passing locally every time.
     *
     * <p>A test that needs a tight timeout now asks for one, instead of every test inheriting it.
     */
    private static final int DEFAULT_READ_TIMEOUT_MS = 2_000;

    /** For the hang test only: shorter than {@code FaultInjectingHttpServer}'s hang, to fail fast. */
    private static final int SHORT_READ_TIMEOUT_MS = 400;

    /** Token endpoint the OAuth2 fixture resolves {@code env:TOKEN_URL} to; mutable per test. */
    private final AtomicReference<String> tokenUrl = new AtomicReference<>("http://127.0.0.1:1/oauth/token");

    private FaultInjectingHttpServer server;
    private RecordingObserver observer;
    private SupplierBreakerRegistry breakers;
    private SupplierBaseClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = FaultInjectingHttpServer.start();
        observer = new RecordingObserver();
        breakers = new SupplierBreakerRegistry(50f, 10, 5, 30, 3);
        client = newClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private SupplierBaseClient newClient() {
        SecretSchemeRegistry secrets = new SecretSchemeRegistry(List.of(new DynamicSecretResolver(tokenUrl)));
        SupplierAuthStrategies strategies = new SupplierAuthStrategies(List.of(
                new BasicPlusApiKeyAuthStrategy(secrets),
                new BearerTokenAuthStrategy(secrets),
                new OAuth2ClientCredentialsAuthStrategy(
                        secrets, new SupplierHttpClients(), Clock.systemUTC(), 30, 5000, 15000)));
        return new SupplierBaseClient(
                new SupplierHttpClients(),
                strategies,
                breakers,
                new SupplierClientMetrics(new SimpleMeterRegistry()),
                observer,
                Clock.systemUTC(),
                1L);
    }

    // ── Success path ────────────────────────────────────────────────────────────────

    @Nested
    class SuccessPath {

        @Test
        void returnsOkWithBodyAndStampsCorrelationId() {
            server.respondOk("{\"stock\":7}");

            SupplierHttpResponse response = client.exchange(request(HttpMethod.POST, "{\"sku\":\"X\"}", 0, null));

            assertThat(response.outcome()).isEqualTo(ExchangeOutcome.OK);
            assertThat(response.httpStatus()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("{\"stock\":7}");
            assertThat(response.attempts()).isEqualTo(1);
            assertThat(server.header(SupplierCorrelationContext.CORRELATION_HEADER))
                    .as("every exchange must be traceable")
                    .hasSize(1);
        }

        /**
         * A vendor's declared charset is honoured, so the audit trail records what the vendor actually
         * said rather than a UTF-8 misreading of it.
         *
         * <p>EDIFACT is Latin-1 by convention. Decoding it as UTF-8 turns every byte in
         * {@code 0x80-0xFF} into {@code U+FFFD}: the exchange still succeeds, so nothing surfaces, and the
         * mangled text is what gets encrypted into the exchange audit as the commercial record. The loss is
         * silent and unrecoverable, which is why it is pinned before any codec ships.
         */
        @Test
        void aDeclaredCharsetIsHonouredSoTheAuditRecordsWhatTheVendorActuallySent() {
            // Accented characters only: in Latin-1 each is a single byte >= 0x80 that is not valid UTF-8,
            // so a UTF-8 read substitutes U+FFFD instead of failing loudly.
            String vendorText = "Pneus Michelin - Ø205 côté gauche";
            server.respondOkRaw("text/plain; charset=ISO-8859-1", vendorText.getBytes(StandardCharsets.ISO_8859_1));

            SupplierHttpResponse response = client.exchange(request(HttpMethod.GET, null, 0, null));

            assertThat(response.outcome()).isEqualTo(ExchangeOutcome.OK);
            assertThat(response.body())
                    .as("the vendor declared ISO-8859-1; reading it as UTF-8 would corrupt the stored record")
                    .isEqualTo(vendorText)
                    .doesNotContain("�");
        }

        @Test
        void anUndeclaredCharsetFallsBackToUtf8NotThePlatformDefault() {
            String utf8Text = "{\"name\":\"Michelin Ø205\"}";
            server.respondOkRaw("application/json", utf8Text.getBytes(StandardCharsets.UTF_8));

            SupplierHttpResponse response = client.exchange(request(HttpMethod.GET, null, 0, null));

            assertThat(response.body()).isEqualTo(utf8Text);
        }

        @Test
        void reusesAnAmbientCorrelationIdSoAnExchangeTracesToTheActionThatCausedIt() {
            server.respondOk("{}");
            String inbound = "corr-from-inbound-request";

            SupplierCorrelationContext.withCorrelationId(inbound, () -> {
                SupplierHttpResponse response = client.exchange(request(HttpMethod.GET, null, 0, null));
                assertThat(response.correlationId()).isEqualTo(inbound);
            });

            assertThat(server.header(SupplierCorrelationContext.CORRELATION_HEADER))
                    .containsExactly(inbound);
        }

        @Test
        void generatesACorrelationIdWhenThereIsNoAmbientScope() {
            // The scheduler path: no inbound request, but the exchange must still be traceable.
            server.respondOk("{}");

            SupplierHttpResponse response = client.exchange(request(HttpMethod.GET, null, 0, null));

            assertThat(response.correlationId()).isNotBlank();
        }

        @Test
        void appliesCredentialsOnTheWire() {
            server.respondOk("{}");

            client.exchange(request(HttpMethod.GET, null, 0, null));

            assertThat(server.header("Authorization")).hasSize(1);
            assertThat(server.header("apikey")).containsExactly("vendor-apikey");
        }
    }

    // ── Classification and retry (ADR-0052 §5) ──────────────────────────────────────

    @Nested
    class ClassificationAndRetry {

        @Test
        void connectionRefusedIsPreSendAndIsRetried() throws IOException {
            String refused = FaultInjectingHttpServer.refusedBaseUrl();

            SupplierHttpResponse response = client.exchange(request(HttpMethod.POST, "{}", 2, refused));

            assertThat(response.outcome()).isEqualTo(ExchangeOutcome.PRE_SEND_FAILURE);
            assertThat(response.isSafeToRedispatch()).isTrue();
            assertThat(response.attempts())
                    .as("a demonstrably untransmitted request is safe to retry, so the budget is used")
                    .isEqualTo(3);
            assertThat(observer.outcomes())
                    .as("every attempt is observed, not just the last")
                    .containsExactly(
                            ExchangeOutcome.PRE_SEND_FAILURE,
                            ExchangeOutcome.PRE_SEND_FAILURE,
                            ExchangeOutcome.PRE_SEND_FAILURE);
        }

        @Test
        void readTimeoutIsAmbiguousAndIsNeverRetried() {
            // THE critical assertion of this class. The body reached the vendor; a retry could
            // create a second purchase order.
            server.hang();

            // The one place a tight read budget is the subject rather than an inherited constraint.
            SupplierHttpResponse response =
                    client.exchange(request(HttpMethod.POST, "{\"order\":1}", 3, null, SHORT_READ_TIMEOUT_MS));

            assertThat(response.outcome()).isEqualTo(ExchangeOutcome.POST_SEND_AMBIGUOUS);
            assertThat(response.isSafeToRedispatch()).isFalse();
            assertThat(response.attempts()).isEqualTo(1);
            assertThat(server.receivedRequests())
                    .as("the vendor must have received the document exactly once despite a retry budget of 3")
                    .isEqualTo(1);
        }

        @Test
        void serverErrorIsAmbiguousAndIsNeverRetried() {
            server.respondStatus(503, "unavailable");

            SupplierHttpResponse response = client.exchange(request(HttpMethod.POST, "{\"order\":1}", 3, null));

            assertThat(response.outcome()).isEqualTo(ExchangeOutcome.POST_SEND_AMBIGUOUS);
            assertThat(response.httpStatus()).isEqualTo(503);
            assertThat(server.receivedRequests())
                    .as("a 5xx means the vendor received and acted on the request; resending may duplicate it")
                    .isEqualTo(1);
        }

        @Test
        void clientErrorIsADefinitiveRejectionAndIsNeverRetried() {
            server.respondStatus(422, "{\"error\":\"unknown article\"}");

            SupplierHttpResponse response = client.exchange(request(HttpMethod.POST, "{}", 3, null));

            assertThat(response.outcome()).isEqualTo(ExchangeOutcome.DEFINITIVE_REJECTION);
            assertThat(response.httpStatus()).isEqualTo(422);
            assertThat(response.body()).contains("unknown article");
            assertThat(server.receivedRequests())
                    .as("retrying a definitive rejection reproduces the same rejection")
                    .isEqualTo(1);
        }

        @Test
        void anIdempotentReadMayRetryAnAmbiguousOutcome() {
            // ADR-0052 §5's explicit carve-out: checkpointed-window reads deduplicate downstream.
            server.respondStatus(503, "unavailable");

            SupplierHttpResponse response =
                    client.exchange(request(HttpMethod.GET, null, 2, null).asIdempotentRead());

            assertThat(response.outcome()).isEqualTo(ExchangeOutcome.POST_SEND_AMBIGUOUS);
            assertThat(server.receivedRequests()).isEqualTo(3);
        }

        @Test
        void anIdempotentReadStillDoesNotRetryADefinitiveRejection() {
            server.respondStatus(400, "bad request");

            SupplierHttpResponse response =
                    client.exchange(request(HttpMethod.GET, null, 3, null).asIdempotentRead());

            assertThat(response.outcome()).isEqualTo(ExchangeOutcome.DEFINITIVE_REJECTION);
            assertThat(server.receivedRequests()).isEqualTo(1);
        }

        @Test
        void zeroRetryBudgetMeansExactlyOneAttempt() throws IOException {
            String refused = FaultInjectingHttpServer.refusedBaseUrl();

            SupplierHttpResponse response = client.exchange(request(HttpMethod.POST, "{}", 0, refused));

            assertThat(response.attempts()).isEqualTo(1);
        }

        @Test
        void exponentialBackoffIsAcceptedWithoutChangingRetryCount() throws IOException {
            String refused = FaultInjectingHttpServer.refusedBaseUrl();
            SupplierHttpRequest req = request(HttpMethod.POST, "{}", 2, refused);
            req.binding().profile().setRetryBackoff(RetryBackoff.EXPONENTIAL);

            SupplierHttpResponse response = client.exchange(req);

            assertThat(response.attempts()).isEqualTo(3);
        }
    }

    // ── Timeout classification is type-safe, not message-based ──────────────────────

    /**
     * These tests exist because the module switched to {@code JdkClientHttpRequestFactory}
     * specifically to make connect-vs-read a <em>type</em> distinction. With the fleet's usual
     * {@code SimpleClientHttpRequestFactory} (HttpURLConnection) both arrive as
     * {@link java.net.SocketTimeoutException} separable only by message text, which forced every
     * socket timeout to be treated as ambiguous and therefore never retried.
     *
     * <p>A real connect timeout is not reproducible in CI here — this container answers an
     * unroutable address with an immediate {@code ConnectException} rather than timing out — so the
     * connect path is proven by classifying a synthesized cause. The read path is proven end to end
     * over a real socket.
     */
    @Nested
    class TimeoutClassification {

        @Test
        void connectTimeoutIsPreSendAndThereforeRetryable() {
            ExchangeOutcome outcome = SupplierBaseClient.classifyIoFailure(new ResourceAccessException(
                    "connect timed out", new HttpConnectTimeoutException("connect timed out")));

            assertThat(outcome).isEqualTo(ExchangeOutcome.PRE_SEND_FAILURE);
            assertThat(outcome.isPreSendRetryable()).isTrue();
        }

        /**
         * The ordering trap. {@link HttpConnectTimeoutException} is a <em>subclass</em> of
         * {@link HttpTimeoutException}, so testing the general type first would silently reclassify
         * every connect timeout as ambiguous and stop it being retried — a pure throughput
         * regression with no test to catch it unless this one exists.
         */
        @Test
        void connectTimeoutIsNotSwallowedByTheGeneralTimeoutCheck() {
            assertThat(HttpTimeoutException.class.isAssignableFrom(HttpConnectTimeoutException.class))
                    .as("guard the premise: if this ever stops being true, the ordering comment is stale")
                    .isTrue();

            assertThat(SupplierBaseClient.classifyIoFailure(
                            new ResourceAccessException("x", new HttpConnectTimeoutException("x"))))
                    .isEqualTo(ExchangeOutcome.PRE_SEND_FAILURE);
            assertThat(SupplierBaseClient.classifyIoFailure(
                            new ResourceAccessException("x", new HttpTimeoutException("x"))))
                    .isEqualTo(ExchangeOutcome.POST_SEND_AMBIGUOUS);
        }

        @Test
        void readTimeoutIsAmbiguousByType() {
            assertThat(SupplierBaseClient.classifyIoFailure(
                            new ResourceAccessException("read timed out", new HttpTimeoutException("read timed out"))))
                    .isEqualTo(ExchangeOutcome.POST_SEND_AMBIGUOUS);
        }

        @Test
        void refusedUnknownHostAndNoRouteAreAllPreSend() {
            assertThat(SupplierBaseClient.classifyIoFailure(
                            new ResourceAccessException("x", new java.net.ConnectException("refused"))))
                    .isEqualTo(ExchangeOutcome.PRE_SEND_FAILURE);
            assertThat(SupplierBaseClient.classifyIoFailure(
                            new ResourceAccessException("x", new java.net.UnknownHostException("nope"))))
                    .isEqualTo(ExchangeOutcome.PRE_SEND_FAILURE);
            assertThat(SupplierBaseClient.classifyIoFailure(
                            new ResourceAccessException("x", new java.net.NoRouteToHostException("nope"))))
                    .isEqualTo(ExchangeOutcome.PRE_SEND_FAILURE);
        }

        @Test
        void anUnrecognisedTransportFailureDefaultsToAmbiguousNotRetryable() {
            // Never guess pre-send for an unknown failure: that would authorize an automatic retry.
            assertThat(SupplierBaseClient.classifyIoFailure(
                            new ResourceAccessException("x", new java.io.IOException("something odd"))))
                    .isEqualTo(ExchangeOutcome.POST_SEND_AMBIGUOUS);
        }

        @Test
        void aLegacySocketTimeoutIsStillTreatedAsAmbiguous() {
            // Defence in depth: if the factory is ever reverted, the conservative reading holds.
            assertThat(SupplierBaseClient.classifyIoFailure(
                            new ResourceAccessException("x", new java.net.SocketTimeoutException("Read timed out"))))
                    .isEqualTo(ExchangeOutcome.POST_SEND_AMBIGUOUS);
        }
    }

    // ── Breaker interaction ─────────────────────────────────────────────────────────

    @Nested
    class BreakerInteraction {

        @Test
        void anOpenBreakerFastFailsWithoutNetworkIoAndWithoutBurningRetries() {
            breakers.breakerFor(PROFILE_ID, SupplierCapability.STOCK_INQUIRY).transitionToOpenState();
            server.respondOk("{}");

            SupplierHttpResponse response = client.exchange(request(HttpMethod.POST, "{}", 3, null));

            assertThat(response.outcome()).isEqualTo(ExchangeOutcome.PRE_SEND_FAILURE);
            assertThat(response.attempts())
                    .as("an open breaker must not spin through the retry budget; re-dispatch happens later")
                    .isEqualTo(1);
            assertThat(server.receivedRequests()).isZero();
            assertThat(response.failureDetail()).contains("Circuit breaker is open");
        }

        @Test
        void breakerOpenIsStillObservedSoTheAuditTrailShowsTheSuppressedAttempt() {
            breakers.breakerFor(PROFILE_ID, SupplierCapability.STOCK_INQUIRY).transitionToOpenState();

            client.exchange(request(HttpMethod.POST, "{}", 0, null));

            assertThat(observer.outcomes()).containsExactly(ExchangeOutcome.PRE_SEND_FAILURE);
        }

        @Test
        void aClosedBreakerDoesNotInterfere() {
            server.respondOk("{}");

            SupplierHttpResponse response = client.exchange(request(HttpMethod.POST, "{}", 0, null));

            assertThat(response.outcome()).isEqualTo(ExchangeOutcome.OK);
        }
    }

    // ── Wire contract: content type, encoding, URI safety ───────────────────────────

    @Nested
    class WireContract {

        /**
         * The declared content type must actually reach the vendor. It previously did not: the record
         * mandated it when a body was present and the client then never read it, so every document
         * went out as StringHttpMessageConverter's default text/plain. An EDIWheel A2.5 order POST
         * would earn a 415 or 400, which classifies DEFINITIVE_REJECTION and is never retried — so
         * the transport would confidently report a permanent rejection of a valid document.
         */
        @Test
        void theDeclaredContentTypeIsSentOnTheWire() {
            server.respondOk("{}");

            client.exchange(request(HttpMethod.POST, "<Order/>", 0, null));

            assertThat(server.header("Content-Type")).containsExactly("application/json");
        }

        @Test
        void queryParametersAreEncodedSoAValueCannotRestructureTheRequest() {
            server.respondOk("{}");
            SupplierHttpRequest base = request(HttpMethod.GET, null, 0, null);
            // A raw '&' would silently become a second parameter; '#' would truncate the request.
            SupplierHttpRequest withHostileValue = new SupplierHttpRequest(
                    base.binding(),
                    HttpMethod.GET,
                    null,
                    new java.util.LinkedHashMap<>(Map.of("articleRef", "X&limit=99999")),
                    null,
                    null,
                    null,
                    false);

            String uri = SupplierBaseClient.absoluteUri(withHostileValue);

            assertThat(uri).contains("articleRef=X%26limit%3D99999");
            assertThat(uri).doesNotContain("X&limit=99999");
        }

        @Test
        void spacesAndNonAsciiInQueryValuesAreEncoded() {
            SupplierHttpRequest base = request(HttpMethod.GET, null, 0, null);
            SupplierHttpRequest withUnicode = new SupplierHttpRequest(
                    base.binding(),
                    HttpMethod.GET,
                    null,
                    new java.util.LinkedHashMap<>(Map.of("party", "Größe Reifen")),
                    null,
                    null,
                    null,
                    false);

            String uri = SupplierBaseClient.absoluteUri(withUnicode);

            assertThat(uri).doesNotContain(" ").contains("Gr%C3%B6%C3%9Fe");
        }

        @Test
        void pathSuffixIsAppendedAfterTheBindingPath() {
            SupplierHttpRequest base = request(HttpMethod.GET, null, 0, "https://vendor.test");
            SupplierHttpRequest withSuffix = new SupplierHttpRequest(
                    base.binding(), HttpMethod.GET, "/orders/42", Map.of(), null, null, null, false);

            assertThat(SupplierBaseClient.absoluteUri(withSuffix)).isEqualTo("https://vendor.test/stock/orders/42");
        }

        @Test
        void aTrailingSlashOnTheBaseUrlDoesNotDoubleUp() {
            SupplierHttpRequest base = request(HttpMethod.GET, null, 0, "https://vendor.test/");

            assertThat(SupplierBaseClient.absoluteUri(base)).isEqualTo("https://vendor.test/stock");
        }

        /**
         * A malformed baseUrl previously escaped as a raw {@code IllegalArgumentException} from
         * outside every try block, producing an ADR-0050 §3 leak AND no observer call at all — so the
         * attempt left no audit row, breaking the totality ADR-0050 §7 depends on.
         */
        @Test
        void aMalformedBaseUrlIsATypedConfigurationErrorAndIsStillObserved() {
            SupplierHttpRequest req = request(HttpMethod.GET, null, 0, "ht!tp://  bad url");

            assertThatThrownBy(() -> client.exchange(req))
                    .isInstanceOf(SupplierConfigurationException.class)
                    .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED);

            assertThat(observer.outcomes())
                    .as("an attempt must always leave an audit row, even when its configuration is unusable")
                    .containsExactly(ExchangeOutcome.CONFIGURATION_ERROR);
            assertThat(server.receivedRequests()).isZero();
        }
    }

    // ── Credential-acquisition failures ─────────────────────────────────────────────

    @Nested
    class CredentialAcquisition {

        /**
         * The worst defect of the previous review round. A transport failure of the OAuth2 TOKEN
         * endpoint was reported as SUPPLIER_AUTH_CONFIG_MISSING -> CONFIGURATION_ERROR -> 409,
         * blaming the operator for an env var that was present and correct, and never retried.
         * Nothing reached the vendor's business endpoint, so ADR-0052 §5 makes it pre-send.
         */
        @Test
        void aTokenEndpointTransportFailureIsRetryablePreSendNotAConfigError() throws java.io.IOException {
            SupplierHttpRequest req = oauthRequest(FaultInjectingHttpServer.refusedBaseUrl(), 2);

            SupplierHttpResponse response = client.exchange(req);

            assertThat(response.outcome()).isEqualTo(ExchangeOutcome.PRE_SEND_FAILURE);
            assertThat(response.isSafeToRedispatch())
                    .as("a vendor's token endpoint being down must cost a retry, not a night's orders")
                    .isTrue();
            assertThat(response.attempts()).isEqualTo(3);
            assertThat(response.failureDetail()).contains("Credential acquisition failed at transport level");
        }

        @Test
        void aTokenEndpoint503IsRetryablePreSend() {
            server.respondStatus(503, "token service unavailable");

            SupplierHttpResponse response = client.exchange(oauthRequest(server.baseUrl(), 1));

            assertThat(response.outcome()).isEqualTo(ExchangeOutcome.PRE_SEND_FAILURE);
            assertThat(response.attempts()).isEqualTo(2);
        }

        /**
         * But credentials that are actually WRONG stay a configuration error: retrying a refused
         * client secret would just hammer the vendor's token endpoint forever.
         */
        @Test
        void aTokenEndpoint401IsAConfigurationErrorWithItsOwnCode() {
            server.respondStatus(401, "invalid_client");

            assertThatThrownBy(() -> client.exchange(oauthRequest(server.baseUrl(), 2)))
                    .isInstanceOf(SupplierConfigurationException.class)
                    .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.AUTH_CREDENTIALS_REJECTED)
                    .hasMessageContaining("refused them");
        }

        @Test
        void aTokenResponseWithoutATokenIsItsOwnNonBlamingCode() {
            server.respondOk("{\"token_type\":\"Bearer\"}");

            assertThatThrownBy(() -> client.exchange(oauthRequest(server.baseUrl(), 0)))
                    .isInstanceOf(SupplierConfigurationException.class)
                    .hasFieldOrPropertyWithValue("code", SupplierConfigurationException.AUTH_TOKEN_RESPONSE_INVALID);
        }
        /**
         * A vendor can revoke an access token before its stated expiry. Without dropping the cache on
         * a 401 the same dead token is re-sent until natural expiry -- potentially an hour of
         * guaranteed failures -- and the invalidate() method existed with no caller at all.
         */
        @Test
        void a401DropsTheCachedTokenSoTheNextCallMintsAFreshOne() {
            server.respondOk("{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
            SupplierHttpRequest req = oauthRequest(server.baseUrl(), 0);
            // First call mints and caches a token (the same stub answers both legs).
            client.exchange(req);
            int afterFirst = server.receivedRequests();

            // Now the business endpoint rejects with 401.
            server.respondStatus(401, "token revoked");
            client.exchange(req);
            int afterRejection = server.receivedRequests();

            // A third call must go back to the token endpoint rather than reuse the dead token.
            server.respondOk("{\"access_token\":\"tok-2\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
            client.exchange(req);

            assertThat(server.receivedRequests())
                    .as("after a 401 the cached token must be dropped, so the next call re-authenticates")
                    .isGreaterThan(afterRejection + (afterRejection - afterFirst));
        }
    }

    // ── Redirects mean our configuration is wrong ────────────────────────────────────

    @Nested
    class Redirects {

        @Test
        void a3xxIsAConfigurationErrorNamingTheRedirectTarget() {
            server.respondStatus(301, "moved");

            SupplierHttpResponse response = client.exchange(request(HttpMethod.POST, "{}", 0, null));

            assertThat(response.outcome())
                    .as("a redirect from a configured baseUrl means the baseUrl is wrong, not that the"
                            + " vendor refused the document")
                    .isEqualTo(ExchangeOutcome.CONFIGURATION_ERROR);
            assertThat(response.failureDetail()).contains("does not point at the vendor's API");
        }
    }

    // ── Breaker accounting (what may open it) ───────────────────────────────────────

    @Nested
    class BreakerAccounting {

        /**
         * A definitive rejection must not open the breaker. If it did, five 400s from a codec bug
         * would open it, and the next call would report PRE_SEND_FAILURE — whose
         * isSafeToRedispatch() is true — laundering a permanent vendor rejection into a transient
         * retryable one that an outbox would re-dispatch forever.
         */
        @Test
        void definitiveRejectionsDoNotCountTowardOpeningTheBreaker() {
            server.respondStatus(400, "bad document");
            for (int i = 0; i < 10; i++) {
                client.exchange(request(HttpMethod.POST, "{}", 0, null));
            }

            assertThat(breakers.breakerFor(PROFILE_ID, SupplierCapability.STOCK_INQUIRY)
                            .getState())
                    .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
        }

        @Test
        void ambiguousTransportFailuresDoCountTowardOpeningTheBreaker() {
            server.respondStatus(503, "unavailable");
            for (int i = 0; i < 10; i++) {
                client.exchange(request(HttpMethod.POST, "{}", 0, null));
            }

            assertThat(breakers.breakerFor(PROFILE_ID, SupplierCapability.STOCK_INQUIRY)
                            .getState())
                    .as("genuine transport unhealthiness is exactly what the breaker is for")
                    .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
        }

        @Test
        void theClassificationPredicateIsTheSingleSourceOfTruth() {
            assertThat(SupplierBreakerRegistry.countsAsTransportFailure(new IllegalStateException("unexpected")))
                    .as("an unexpected exception type is genuinely unexpected and must count")
                    .isTrue();
        }
    }

    // ── Configuration errors ────────────────────────────────────────────────────────

    @Nested
    class ConfigurationErrors {

        @Test
        void unresolvableCredentialFailsBeforeAnyNetworkUseAndIsObserved() {
            SupplierHttpRequest req = request(HttpMethod.POST, "{}", 0, null);
            req.binding().authConfig().setPasswordRef("env:NOT_SET");

            assertThatThrownBy(() -> client.exchange(req)).isInstanceOf(SupplierConfigurationException.class);

            assertThat(server.receivedRequests())
                    .as("credential resolution must fail before the socket is touched")
                    .isZero();
            assertThat(observer.outcomes()).containsExactly(ExchangeOutcome.CONFIGURATION_ERROR);
        }
    }

    // ── Observation ─────────────────────────────────────────────────────────────────

    @Nested
    class Observation {

        @Test
        void observerReceivesIdentityAndNoCredentialMaterial() {
            server.respondOk("{\"ok\":true}");

            client.exchange(request(HttpMethod.POST, "{\"sku\":\"X\"}", 0, null));

            ExchangeContext context = observer.contexts().getFirst();
            assertThat(context.vendorProfileId()).isEqualTo(PROFILE_ID);
            assertThat(context.supplierRef()).isEqualTo("michelin-eu");
            assertThat(context.capability()).isEqualTo(SupplierCapability.STOCK_INQUIRY);
            assertThat(context.bindingId()).isEqualTo(BINDING_ID);
            assertThat(context.attempt()).isEqualTo(1);
            assertThat(context.httpStatus()).isEqualTo(200);
            assertThat(context.toString())
                    .as("the observer payload must never carry a resolved credential")
                    .doesNotContain("vendor-password")
                    .doesNotContain("vendor-apikey");
        }

        @Test
        void anObserverThatThrowsDoesNotFailASuccessfulExchange() {
            // ADR-0050 §7: auditing is a side concern; it must not turn a good exchange into a bad one.
            server.respondOk("{}");
            SupplierBaseClient clientWithBadObserver = new SupplierBaseClient(
                    new SupplierHttpClients(),
                    new SupplierAuthStrategies(List.of(
                            new BasicPlusApiKeyAuthStrategy(secrets()),
                            new BearerTokenAuthStrategy(secrets()),
                            new OAuth2ClientCredentialsAuthStrategy(
                                    secrets(), new SupplierHttpClients(), Clock.systemUTC(), 30, 5000, 15000))),
                    breakers,
                    new SupplierClientMetrics(new SimpleMeterRegistry()),
                    context -> {
                        throw new IllegalStateException("audit sink is down");
                    },
                    Clock.systemUTC(),
                    1L);

            SupplierHttpResponse response = clientWithBadObserver.exchange(request(HttpMethod.GET, null, 0, null));

            assertThat(response.outcome()).isEqualTo(ExchangeOutcome.OK);
        }
    }

    // ── Sandbox overlay ─────────────────────────────────────────────────────────────

    @Nested
    class SandboxOverlay {

        @Test
        void sandboxOverrideReplacesTheBindingBaseUrl() {
            SupplierHttpRequest req = request(HttpMethod.GET, null, 0, "https://production.invalid");
            req.binding().profile().setSandbox(true);
            req.binding().profile().setSandboxBaseUrlOverride(server.baseUrl());
            server.respondOk("{}");

            SupplierHttpResponse response = client.exchange(req);

            assertThat(response.outcome())
                    .as("with sandbox on, traffic must go to the override, not the production base URL")
                    .isEqualTo(ExchangeOutcome.OK);
            assertThat(server.receivedRequests()).isEqualTo(1);
        }

        @Test
        void theOverrideIsIgnoredWhenSandboxIsOff() {
            SupplierHttpRequest req = request(HttpMethod.GET, null, 0, null);
            req.binding().profile().setSandbox(false);
            req.binding().profile().setSandboxBaseUrlOverride("https://sandbox.invalid");
            server.respondOk("{}");

            assertThat(client.exchange(req).outcome()).isEqualTo(ExchangeOutcome.OK);
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────

    private SecretSchemeRegistry secrets() {
        return new SecretSchemeRegistry(List.of(new DynamicSecretResolver(tokenUrl)));
    }

    /** A binding whose auth is OAuth2, with the token endpoint at {@code tokenBaseUrl}. */
    private SupplierHttpRequest oauthRequest(String tokenBaseUrl, int maxRetries) {
        SupplierHttpRequest base = request(HttpMethod.POST, "{}", maxRetries, null);
        SupplierAuthConfigEntity auth = base.binding().authConfig();
        auth.setType(SupplierAuthType.OAUTH2_CLIENT_CREDENTIALS);
        auth.setUsernameRef(null);
        auth.setPasswordRef(null);
        auth.setApiKeyRef(null);
        auth.setApiKeyHeader(null);
        auth.setTokenUrlRef("env:TOKEN_URL");
        auth.setClientIdRef("env:CLIENT_ID");
        auth.setClientSecretRef("env:CLIENT_SECRET");
        tokenUrl.set(tokenBaseUrl + "/oauth/token");
        return base;
    }

    private SupplierHttpRequest request(HttpMethod method, String body, int maxRetries, String baseUrlOverride) {
        return request(method, body, maxRetries, baseUrlOverride, DEFAULT_READ_TIMEOUT_MS);
    }

    private SupplierHttpRequest request(
            HttpMethod method, String body, int maxRetries, String baseUrlOverride, int readTimeoutMs) {
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef("michelin-eu");
        profile.setDisplayName("Michelin EU");
        profile.setEnabled(true);
        profile.setConnectTimeoutMs(1_000);
        profile.setReadTimeoutMs(readTimeoutMs);
        profile.setRetryMaxAttempts(maxRetries);

        SupplierEndpointBindingEntity binding = new SupplierEndpointBindingEntity();
        binding.setId(BINDING_ID);
        binding.setVendorProfileId(PROFILE_ID);
        binding.setCapability(SupplierCapability.STOCK_INQUIRY);
        binding.setProtocolFamily(ProtocolFamily.EDIWHEEL_A25);
        binding.setProtocolVersion("A2_5");
        binding.setBaseUrl(baseUrlOverride == null ? server.baseUrl() : baseUrlOverride);
        binding.setPath("/stock");
        binding.setAuthConfigName("ediwheel-basic");
        binding.setEnabled(true);

        SupplierAuthConfigEntity auth = new SupplierAuthConfigEntity();
        auth.setId(AUTH_ID);
        auth.setVendorProfileId(PROFILE_ID);
        auth.setName("ediwheel-basic");
        auth.setType(SupplierAuthType.BASIC_PLUS_APIKEY);
        auth.setUsernameRef("env:M_USER");
        auth.setPasswordRef("env:M_PASSWORD");
        auth.setApiKeyRef("env:M_APIKEY");
        auth.setApiKeyHeader("apikey");

        ResolvedBinding resolved = new ResolvedBinding(
                profile,
                binding,
                auth,
                SupplierCapability.STOCK_INQUIRY,
                ProtocolFamily.EDIWHEEL_A25,
                new ProtocolVersion("A2_5"));
        return new SupplierHttpRequest(
                resolved, method, null, Map.of(), body, body == null ? null : "application/json", null, false);
    }

    /** Captures every observation so tests can assert on per-attempt behaviour. */
    private static final class RecordingObserver implements ExchangeObserver {

        private final List<ExchangeContext> contexts = new CopyOnWriteArrayList<>();

        @Override
        public void onExchange(@NonNull ExchangeContext context) {
            contexts.add(context);
        }

        List<ExchangeContext> contexts() {
            return List.copyOf(contexts);
        }

        List<ExchangeOutcome> outcomes() {
            return contexts.stream().map(ExchangeContext::outcome).toList();
        }
    }

    /**
     * Resolver claiming {@code env}. TOKEN_URL comes from a holder so a test can point the OAuth2
     * token endpoint at a specific server, or a refused port, without rebuilding the client.
     */
    private record DynamicSecretResolver(AtomicReference<String> tokenUrl) implements SecretReferenceResolver {

        @Override
        @NonNull
        public String scheme() {
            return "env";
        }

        @Override
        @NonNull
        public String resolve(@NonNull String reference) {
            String key = reference.substring(reference.indexOf(':') + 1);
            String value =
                    switch (key) {
                        case "M_USER" -> "vendor-user";
                        case "M_PASSWORD" -> "vendor-password";
                        case "M_APIKEY" -> "vendor-apikey";
                        case "CLIENT_ID" -> "client-id";
                        case "CLIENT_SECRET" -> "client-secret";
                        case "TOKEN_URL" -> tokenUrl.get();
                        default -> null;
                    };
            if (value == null) {
                throw new SupplierConfigurationException(
                        SupplierConfigurationException.SECRET_NOT_FOUND,
                        "Secret reference '" + reference + "' resolves to no value");
            }
            return value;
        }
    }
}
