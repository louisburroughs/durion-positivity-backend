package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.RetryBackoff;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.internal.spi.ExchangeContext;
import com.positivity.supplier.internal.spi.ExchangeObserver;
import com.positivity.supplier.internal.spi.ExchangeOutcome;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The shared outbound transport for every supplier capability: per-binding timeouts, call-time
 * credential application, correlation stamping, ADR-0052 §5 outcome classification, pre-send-only
 * retry, a circuit breaker per {@code (vendorProfileId, capability)}, and an
 * {@link ExchangeObserver} notification for <em>every</em> attempt.
 *
 * <h2>Retry semantics (ADR-0052 §5) — the part most likely to be broken by a later edit</h2>
 *
 * Only {@link ExchangeOutcome#PRE_SEND_FAILURE} is retried in-call, and only when the failure
 * demonstrably preceded transmission. A read timeout is <strong>not</strong> such a case: the vendor
 * may already hold the order, so retrying could duplicate it. Requests explicitly marked
 * {@link SupplierHttpRequest#idempotent()} may additionally retry an ambiguous outcome, which
 * ADR-0052 §5 permits only for checkpointed-window reads.
 *
 * <p>An <strong>open breaker is classified pre-send but never retried in-call.</strong> "Safe to
 * re-dispatch" in ADR-0052 §3 means safe to re-dispatch <em>later from the outbox</em>; spinning
 * against an open breaker inside one call just burns the retry budget without touching the network.
 *
 * <h2>Deliberately conservative timeout classification</h2>
 *
 * {@link java.net.SocketTimeoutException} covers both connect and read timeouts, and the JDK
 * distinguishes them only by message text. Rather than depend on that, any socket timeout is
 * classified {@link ExchangeOutcome#POST_SEND_AMBIGUOUS}. The risk is asymmetric: calling a connect
 * timeout "ambiguous" costs one retry we could safely have made, whereas calling a read timeout
 * "pre-send" costs a duplicate purchase order at a vendor. Connection refused, unknown host and no
 * route are definitively pre-send and keep their retryable classification.
 */
@Component
public class SupplierBaseClient {

    private static final Logger log = LoggerFactory.getLogger(SupplierBaseClient.class);

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_MAX_ATTEMPTS = 1;

    private final SupplierAuthStrategies authStrategies;
    private final SupplierBreakerRegistry breakerRegistry;
    private final SupplierClientMetrics metrics;
    private final ExchangeObserver exchangeObserver;
    private final Clock clock;
    private final long baseBackoffMillis;

    /** RestClients cached by timeout signature; building one per call would waste connection pools. */
    private final ConcurrentMap<String, RestClient> clientsByTimeouts = new ConcurrentHashMap<>();

    public SupplierBaseClient(
            @NonNull SupplierAuthStrategies authStrategies,
            @NonNull SupplierBreakerRegistry breakerRegistry,
            @NonNull SupplierClientMetrics metrics,
            @NonNull ExchangeObserver exchangeObserver,
            @NonNull Clock clock,
            @Value("${pos.supplier.retry.base-backoff-millis:500}") long baseBackoffMillis) {
        this.authStrategies = Objects.requireNonNull(authStrategies, "authStrategies");
        this.breakerRegistry = Objects.requireNonNull(breakerRegistry, "breakerRegistry");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.exchangeObserver = Objects.requireNonNull(exchangeObserver, "exchangeObserver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.baseBackoffMillis = baseBackoffMillis;
    }

    /**
     * Executes one logical call, retrying only pre-send failures.
     *
     * @param request what to send
     * @return the transport outcome; failures are returned, not thrown
     * @throws SupplierConfigurationException when credentials cannot be resolved — a deployment
     *     defect, raised before any network I/O and after the attempt is reported to the observer
     */
    @NonNull
    public SupplierHttpResponse exchange(@NonNull SupplierHttpRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ResolvedBinding binding = request.binding();
        SupplierProfileEntity profile = binding.profile();
        String correlationId = SupplierCorrelationContext.currentOrGenerate();
        CircuitBreaker breaker = breakerRegistry.breakerFor(profile.getVendorProfileId(), binding.capability());
        Instant callStarted = Instant.now(clock);
        AttemptCounter counter = new AttemptCounter();

        // Resolve the URI once, here, INSIDE the observed region. Building it lazily further down
        // meant a malformed baseUrl/path escaped as a raw IllegalArgumentException with no observer
        // call at all -- an ADR-0050 §3 leak AND a missing audit row, which breaks the totality
        // ADR-0050 §7 depends on.
        String uri;
        try {
            uri = absoluteUri(request);
        } catch (RuntimeException ex) {
            // rawTarget is plain concatenation and cannot throw, so the observation always has a
            // usable uri field even when the configured value is unusable.
            recordAndBuild(
                    request,
                    rawTarget(request),
                    correlationId,
                    1,
                    ExchangeOutcome.CONFIGURATION_ERROR,
                    null,
                    null,
                    "Endpoint URI could not be built from the binding's baseUrl, path and parameters",
                    callStarted,
                    Instant.now(clock));
            throw new SupplierConfigurationException(
                    SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED,
                    "Binding " + binding.binding().getId() + " for capability " + binding.capability()
                            + " does not produce a usable endpoint URI; check its baseUrl and path");
        }

        // Breaker checked before any attempt: an open breaker must fast-fail without network I/O
        // and without consuming the retry budget.
        if (!breaker.tryAcquirePermission()) {
            metrics.recordBreakerState(profile.getVendorProfileId(), binding.capability(), breaker);
            return recordAndBuild(
                    request,
                    uri,
                    correlationId,
                    1,
                    ExchangeOutcome.PRE_SEND_FAILURE,
                    null,
                    null,
                    "Circuit breaker is open for this supplier capability; no request was sent",
                    callStarted,
                    Instant.now(clock));
        }
        // Permission acquired above is consumed by the first attempt's own acquire inside
        // executeSupplier; release it so the breaker's in-flight accounting stays balanced.
        breaker.releasePermission();

        Retry retry = retryFor(request, profile);
        Supplier<SupplierHttpResponse> decorated = Retry.decorateSupplier(
                retry, () -> breaker.executeSupplier(() -> attempt(request, uri, correlationId, counter, breaker)));

        try {
            SupplierHttpResponse response = decorated.get();
            metrics.recordBreakerState(profile.getVendorProfileId(), binding.capability(), breaker);
            return withTotals(response, counter.value(), callStarted);
        } catch (CallNotPermittedException ex) {
            // The breaker opened mid-retry.
            metrics.recordBreakerState(profile.getVendorProfileId(), binding.capability(), breaker);
            return recordAndBuild(
                    request,
                    uri,
                    correlationId,
                    counter.valueAtLeastOne(),
                    ExchangeOutcome.PRE_SEND_FAILURE,
                    null,
                    null,
                    "Circuit breaker opened while retrying; remaining attempts were not sent",
                    callStarted,
                    Instant.now(clock));
        } catch (TransportAttemptException ex) {
            // A non-retryable outcome, or the retry budget was exhausted. Already observed.
            metrics.recordBreakerState(profile.getVendorProfileId(), binding.capability(), breaker);
            return withTotals(ex.response(), counter.valueAtLeastOne(), callStarted);
        }
    }

    // ── One attempt ─────────────────────────────────────────────────────────────────

    @NonNull
    private SupplierHttpResponse attempt(
            @NonNull SupplierHttpRequest request,
            @NonNull String uri,
            @NonNull String correlationId,
            @NonNull AttemptCounter counter,
            @NonNull CircuitBreaker breaker) {
        int attemptNumber = counter.next();
        ResolvedBinding binding = request.binding();
        Instant started = Instant.now(clock);

        HttpHeaders headers = new HttpHeaders();
        headers.set(SupplierCorrelationContext.CORRELATION_HEADER, correlationId);
        if (request.accept() != null) {
            headers.set(HttpHeaders.ACCEPT, request.accept());
        }
        if (request.contentType() != null) {
            // Without this the body went out as StringHttpMessageConverter's default (text/plain),
            // so the first EDIWheel order POST would earn a 415/400 -- classified DEFINITIVE_REJECTION
            // and therefore never retried, reporting a permanent rejection of a valid document.
            headers.set(HttpHeaders.CONTENT_TYPE, request.contentType());
        }
        try {
            // Credentials resolved at call time; the resolved values live only in these headers.
            authStrategies.apply(headers, binding.authConfig());
        } catch (SupplierAuthTransportException ex) {
            // The token leg failed at transport level. Nothing reached the vendor's BUSINESS
            // endpoint, so this is pre-send and retryable (ADR-0052 §5) -- returned rather than
            // thrown, so a caller can consult isSafeToRedispatch().
            throw failed(recordAndBuild(
                    request,
                    uri,
                    correlationId,
                    attemptNumber,
                    ExchangeOutcome.PRE_SEND_FAILURE,
                    null,
                    null,
                    "Credential acquisition failed at transport level before the business request was sent: "
                            + ex.getMessage(),
                    started,
                    Instant.now(clock)));
        } catch (SupplierConfigurationException ex) {
            // Report the attempt, then let it propagate: a deployment defect must not be absorbed.
            recordAndBuild(
                    request,
                    uri,
                    correlationId,
                    attemptNumber,
                    ExchangeOutcome.CONFIGURATION_ERROR,
                    null,
                    null,
                    "Credential resolution failed before any network use",
                    started,
                    Instant.now(clock));
            throw ex;
        }

        try {
            // Read as bytes, decode ourselves: vendors answer with XML, JSON or EDIFACT under
            // assorted (and sometimes absent) content types, and String extraction would make the
            // transport depend on content-type negotiation it has no business caring about.
            ResponseEntity<byte[]> entity = clientFor(binding)
                    .method(request.method())
                    .uri(URI.create(uri))
                    .headers(existing -> existing.addAll(headers))
                    .body(request.body() == null ? "" : request.body())
                    .retrieve()
                    .toEntity(byte[].class);

            int status = entity.getStatusCode().value();
            if (entity.getStatusCode().is3xxRedirection()) {
                // followRedirects(NEVER) means a redirect surfaces as an ordinary response here:
                // Spring's retrieve() only raises for 4xx/5xx. A 3xx from a configured baseUrl says
                // OUR configuration is wrong -- this is not where the API lives -- not that the
                // vendor refused the document. Reporting it as a definitive rejection would tell an
                // operator "the vendor permanently refused this order" when the actionable truth is
                // "fix this profile's baseUrl".
                String location = entity.getHeaders().getFirst(HttpHeaders.LOCATION);
                throw failed(recordAndBuild(
                        request,
                        uri,
                        correlationId,
                        attemptNumber,
                        ExchangeOutcome.CONFIGURATION_ERROR,
                        status,
                        null,
                        "Endpoint redirected (HTTP " + status + ") to '" + (location == null ? "unknown" : location)
                                + "'; the binding's baseUrl does not point at the vendor's API",
                        started,
                        Instant.now(clock)));
            }
            SupplierHttpResponse ok = recordAndBuild(
                    request,
                    uri,
                    correlationId,
                    attemptNumber,
                    ExchangeOutcome.OK,
                    status,
                    decode(entity.getBody()),
                    null,
                    started,
                    Instant.now(clock));
            metrics.recordBreakerState(binding.profile().getVendorProfileId(), binding.capability(), breaker);
            return ok;

        } catch (RestClientResponseException ex) {
            // A response WAS received, so the body reached the vendor: 4xx is a definitive
            // rejection, 5xx is ambiguous (it acted on the request and then failed).
            int status = ex.getStatusCode().value();
            // 3xx cannot reach here: Spring's retrieve() raises only for 4xx/5xx, so redirects are
            // classified on the success path above.
            ExchangeOutcome outcome = ex.getStatusCode().is4xxClientError()
                    ? ExchangeOutcome.DEFINITIVE_REJECTION
                    : ExchangeOutcome.POST_SEND_AMBIGUOUS;
            if (status == 401) {
                // A cached OAuth2 access token can be revoked before its stated expiry. Without
                // dropping it here the same dead token is re-sent until it expires naturally --
                // potentially an hour of guaranteed 401s. Deliberately NOT retried in-call: a 401 is
                // also what a genuinely wrong credential returns, and retrying that would hammer the
                // vendor. Dropping the cache lets the NEXT call recover instead.
                authStrategies.invalidateCachedCredential(binding.authConfig());
            }
            throw failed(recordAndBuild(
                    request,
                    uri,
                    correlationId,
                    attemptNumber,
                    outcome,
                    status,
                    ex.getResponseBodyAsString(),
                    "Vendor returned HTTP " + status,
                    started,
                    Instant.now(clock)));

        } catch (ResourceAccessException ex) {
            ExchangeOutcome outcome = classifyIoFailure(ex);
            throw failed(recordAndBuild(
                    request,
                    uri,
                    correlationId,
                    attemptNumber,
                    outcome,
                    null,
                    null,
                    describeIoFailure(ex, outcome),
                    started,
                    Instant.now(clock)));

        } catch (RestClientException ex) {
            // Catch-all for the rest of the RestClient family (unreadable body, unexpected content
            // type, converter failure). Without this branch such an exception would escape
            // unclassified and reach the caller as a raw Spring type instead of a typed outcome,
            // which is exactly the error leak ADR-0050 §3 forbids. Classified ambiguous because a
            // conversion failure means a response WAS received, so the request was transmitted.
            throw failed(recordAndBuild(
                    request,
                    uri,
                    correlationId,
                    attemptNumber,
                    ExchangeOutcome.POST_SEND_AMBIGUOUS,
                    null,
                    null,
                    "Vendor response could not be processed (" + ex.getClass().getSimpleName() + ")",
                    started,
                    Instant.now(clock)));
        }
    }

    @Nullable
    private static String decode(byte @Nullable [] body) {
        return body == null ? null : new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Classifies a transport I/O failure. Connection refused / unknown host / no route definitively
     * never transmitted; anything else (notably socket timeouts) is treated as ambiguous, because
     * misclassifying a read timeout as pre-send risks a duplicate order.
     */
    @NonNull
    static ExchangeOutcome classifyIoFailure(@NonNull ResourceAccessException ex) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            ExchangeOutcome classified = classifyCause(cause);
            if (classified != null) {
                return classified;
            }
        }
        // Unknown transport failure: assume the request may have been transmitted. Never guess
        // "pre-send" here, because that would authorize an automatic retry.
        return ExchangeOutcome.POST_SEND_AMBIGUOUS;
    }

    /**
     * Classifies a single cause, or {@code null} to keep walking the chain.
     *
     * <p><strong>Order is load-bearing.</strong> {@link HttpConnectTimeoutException} is a
     * <em>subclass</em> of {@link HttpTimeoutException}, so the specific connect case must be tested
     * before the general timeout case; reversing these two lines would silently reclassify every
     * connect timeout as ambiguous and stop it being retried.
     */
    @Nullable
    private static ExchangeOutcome classifyCause(@NonNull Throwable cause) {
        if (cause instanceof HttpConnectTimeoutException) {
            // The connection itself never completed, so nothing was transmitted.
            return ExchangeOutcome.PRE_SEND_FAILURE;
        }
        if (cause instanceof ConnectException
                || cause instanceof UnknownHostException
                || cause instanceof NoRouteToHostException) {
            return ExchangeOutcome.PRE_SEND_FAILURE;
        }
        if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
            // A response never arrived, but the request may already be with the vendor.
            return ExchangeOutcome.POST_SEND_AMBIGUOUS;
        }
        return null;
    }

    @NonNull
    private static String describeIoFailure(@NonNull ResourceAccessException ex, @NonNull ExchangeOutcome outcome) {
        String kind = outcome == ExchangeOutcome.PRE_SEND_FAILURE
                ? "Connection could not be established; the request was never transmitted"
                : "Transport failed after the request may have been transmitted; outcome is unknown";
        // Class name only: an exception message can echo a URI, and URIs come from configuration.
        return kind + " ("
                + (ex.getCause() == null
                        ? ex.getClass().getSimpleName()
                        : ex.getCause().getClass().getSimpleName()) + ")";
    }

    // ── Retry / transport plumbing ──────────────────────────────────────────────────

    /**
     * Retry built per call from the profile's current settings, so an admin edit takes effect on the
     * next exchange rather than at the next restart.
     */
    @NonNull
    private Retry retryFor(@NonNull SupplierHttpRequest request, @NonNull SupplierProfileEntity profile) {
        Integer configured = profile.getRetryMaxAttempts();
        int maxAttempts = configured == null || configured < 0 ? DEFAULT_MAX_ATTEMPTS : configured + 1;
        RetryBackoff backoff = profile.getRetryBackoff();
        IntervalFunction interval = backoff == RetryBackoff.EXPONENTIAL
                ? IntervalFunction.ofExponentialBackoff(Duration.ofMillis(baseBackoffMillis), 2.0)
                : IntervalFunction.of(Duration.ofMillis(baseBackoffMillis));

        return Retry.of(
                "supplier." + profile.getVendorProfileId() + "."
                        + request.binding().capability(),
                RetryConfig.custom()
                        .maxAttempts(Math.max(1, maxAttempts))
                        .intervalFunction(interval)
                        .retryOnException(throwable -> isRetryable(throwable, request.idempotent()))
                        .build());
    }

    /**
     * The single decision point for "may this be retried in-call". Pre-send failures always;
     * ambiguous outcomes only for explicitly idempotent reads (ADR-0052 §5). Definitive rejections
     * and configuration errors never.
     */
    static boolean isRetryable(@NonNull Throwable throwable, boolean idempotent) {
        if (!(throwable instanceof TransportAttemptException attempt)) {
            return false;
        }
        ExchangeOutcome outcome = attempt.response().outcome();
        if (outcome == ExchangeOutcome.PRE_SEND_FAILURE) {
            return true;
        }
        return idempotent && outcome == ExchangeOutcome.POST_SEND_AMBIGUOUS;
    }

    @NonNull
    private RestClient clientFor(@NonNull ResolvedBinding binding) {
        SupplierProfileEntity profile = binding.profile();
        int connectTimeout =
                profile.getConnectTimeoutMs() == null ? DEFAULT_CONNECT_TIMEOUT_MS : profile.getConnectTimeoutMs();
        int readTimeout = profile.getReadTimeoutMs() == null ? DEFAULT_READ_TIMEOUT_MS : profile.getReadTimeoutMs();
        return clientsByTimeouts.computeIfAbsent(connectTimeout + ":" + readTimeout, key -> {
            // JdkClientHttpRequestFactory, NOT the SimpleClientHttpRequestFactory used elsewhere in
            // the fleet. This module's correctness rests on telling a connect timeout (never
            // transmitted, safe to retry) from a read timeout (may have been transmitted, must NOT
            // be retried), and SimpleClientHttpRequestFactory wraps HttpURLConnection, which
            // reports both as java.net.SocketTimeoutException separable only by message text.
            // java.net.http.HttpClient raises HttpConnectTimeoutException as a distinct type, so
            // the classification is type-safe. It also pools connections, which HttpURLConnection
            // does not, and this transport makes repeated calls per vendor.
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeout))
                    // Explicit: never follow a vendor redirect. Silently re-sending a
                    // state-creating POST body to another host is exactly the duplicate-submission
                    // risk ADR-0052 exists to prevent.
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            // Connect timeout lives on the HttpClient; only the read timeout is per-request here.
            factory.setReadTimeout(Duration.ofMillis(readTimeout));
            return RestClient.builder().requestFactory(factory).build();
        });
    }

    /**
     * Absolute URI for an attempt: the sandbox overlay replaces the binding's base URL when the
     * profile is in sandbox mode and supplies an override (ADR-0050 §2).
     */
    @NonNull
    static String absoluteUri(@NonNull SupplierHttpRequest request) {
        ResolvedBinding binding = request.binding();
        SupplierProfileEntity profile = binding.profile();
        SupplierEndpointBindingEntity endpoint = binding.binding();
        String baseUrl = endpoint.getBaseUrl();
        if (profile.isSandbox()
                && profile.getSandboxBaseUrlOverride() != null
                && !profile.getSandboxBaseUrlOverride().isBlank()) {
            baseUrl = profile.getSandboxBaseUrlOverride();
        }
        String path = endpoint.getPath() == null ? "" : endpoint.getPath();
        String suffix = request.pathSuffix() == null ? "" : request.pathSuffix();
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(trimTrailingSlash(baseUrl) + path + suffix);
        for (Map.Entry<String, String> param : request.queryParams().entrySet()) {
            builder.queryParam(param.getKey(), param.getValue());
        }
        // encode(): query values routinely carry European article and party references with
        // spaces, non-ASCII, '&' or '='. Unencoded, a value containing '&' silently becomes a second
        // parameter and a '#' truncates the rest of the request. Base URLs and paths are therefore
        // expected unencoded in configuration, which is how operators write them.
        return builder.encode().build().toUriString();
    }

    /**
     * The target as plain string concatenation, with no parsing and no encoding, so it cannot throw.
     * Used only to populate an observation when {@link #absoluteUri} itself failed — an attempt must
     * always produce an audit row (ADR-0050 §7), including when its configuration is unusable.
     *
     * @param request the request whose binding is being described
     * @return a best-effort description of the intended target
     */
    @NonNull
    static String rawTarget(@NonNull SupplierHttpRequest request) {
        SupplierEndpointBindingEntity endpoint = request.binding().binding();
        return String.valueOf(endpoint.getBaseUrl())
                + String.valueOf(endpoint.getPath())
                + (request.pathSuffix() == null ? "" : request.pathSuffix());
    }

    @NonNull
    private static String trimTrailingSlash(@NonNull String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    // ── Observation ─────────────────────────────────────────────────────────────────

    /**
     * Builds the response for one attempt and reports it to the observer and metrics. Observer
     * failure is an observability problem, never a transport failure: a successful vendor exchange
     * must not be reported as failed because auditing had a bad day (ADR-0050 §7).
     */
    @NonNull
    private SupplierHttpResponse recordAndBuild(
            @NonNull SupplierHttpRequest request,
            @NonNull String uri,
            @NonNull String correlationId,
            int attemptNumber,
            @NonNull ExchangeOutcome outcome,
            @Nullable Integer httpStatus,
            @Nullable String responseBody,
            @Nullable String failureDetail,
            @NonNull Instant started,
            @NonNull Instant finished) {
        ResolvedBinding binding = request.binding();
        Duration duration = Duration.between(started, finished);
        SupplierHttpResponse response = new SupplierHttpResponse(
                outcome, httpStatus, responseBody, correlationId, attemptNumber, duration, failureDetail);

        try {
            exchangeObserver.onExchange(new ExchangeContext(
                    binding.profile().getVendorProfileId(),
                    binding.profile().getSupplierRef(),
                    binding.capability(),
                    binding.family(),
                    binding.version().value(),
                    binding.binding().getId(),
                    request.method().name(),
                    uri,
                    attemptNumber,
                    correlationId,
                    outcome,
                    httpStatus,
                    started,
                    duration,
                    request.body(),
                    responseBody,
                    failureDetail));
        } catch (RuntimeException observerFailure) {
            log.error(
                    "ExchangeObserver failed for supplier {} capability {}; the exchange itself is unaffected",
                    binding.profile().getSupplierRef(),
                    binding.capability(),
                    observerFailure);
        }
        metrics.recordExchange(binding.profile().getVendorProfileId(), binding.capability(), outcome, duration);
        return response;
    }

    @NonNull
    private SupplierHttpResponse withTotals(
            @NonNull SupplierHttpResponse response, int attempts, @NonNull Instant callStarted) {
        return new SupplierHttpResponse(
                response.outcome(),
                response.httpStatus(),
                response.body(),
                response.correlationId(),
                attempts,
                Duration.between(callStarted, Instant.now(clock)),
                response.failureDetail());
    }

    @NonNull
    private static TransportAttemptException failed(@NonNull SupplierHttpResponse response) {
        return new TransportAttemptException(response);
    }

    /**
     * Internal signal carrying a failed attempt's already-observed response out to the retry
     * decorator. Never escapes this class: {@link #exchange} converts it back into a response.
     */
    static final class TransportAttemptException extends RuntimeException implements ExchangeOutcomeCarrier {

        private final transient SupplierHttpResponse response;

        TransportAttemptException(@NonNull SupplierHttpResponse response) {
            // No message, no cause, no stack trace: this is control flow, not an error report, and
            // it is thrown on every failed attempt.
            super(null, null, false, false);
            this.response = response;
        }

        @NonNull
        SupplierHttpResponse response() {
            return response;
        }

        @Override
        @NonNull
        public ExchangeOutcome exchangeOutcome() {
            return response.outcome();
        }
    }

    /** Counts attempts across retries so each observation carries a correct attempt number. */
    private static final class AttemptCounter {

        private int value;

        int next() {
            return ++value;
        }

        int value() {
            return value;
        }

        int valueAtLeastOne() {
            return Math.max(1, value);
        }
    }
}
