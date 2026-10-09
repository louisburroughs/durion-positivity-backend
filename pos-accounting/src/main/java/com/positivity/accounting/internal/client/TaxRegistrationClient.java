package com.positivity.accounting.internal.client;

import com.positivity.accounting.internal.dto.ChangeTaxRegistrationRequest;
import com.positivity.accounting.internal.dto.RecordTaxRegistrationRequest;
import com.positivity.accounting.internal.exception.TaxRegistrationRelayException;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.shared.error.ApiError;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantHeaders;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * pos-accounting's front-door call to pos-tax's tax-registration writes (CAP:550 S32c; ADR-0071 §5-6, AW59).
 *
 * <p>pos-tax is a utility (ADR-0044 R2), internal-only and not on Eureka, so it is reached on a fixed base URL. Each
 * call carries the inbound {@code X-Correlation-Id}, accounting's per-caller secret ({@value #SECRET_HEADER}, {@code pos.accounting.tax.front-door-secret}),
 * the actor exactly as the gateway forwarded it ({@code X-User-Id}) and the bound tenant ({@code X-Tenant-Id}).
 *
 * <p>pos-tax's 400, 404, 409 and 422 are relayed unchanged ({@link TaxRegistrationRelayException}). Anything else is 503
 * {@code SERVICE_UNAVAILABLE} ({@link TaxServiceUnavailableException}): pos-tax unreachable or failing, a blank
 * secret here, or a 401 there (this service's secret is wrong). Nothing is stored here either way. Nothing logs a
 * request or response body, which carry a registration number, or the secret.
 */
@Slf4j
@Component
public class TaxRegistrationClient {

    public static final String SECRET_HEADER = "X-Pos-Tax-Front-Door-Secret";
    public static final String ACTOR_HEADER = "X-User-Id";

    private static final String PATH = "/v1/tax/registrations";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    /** Name of the counter of pos-tax 401s: this service's secret does not match pos-tax's. */
    public static final String SECRET_REFUSED_COUNTER = "accounting.tax_registration.front_door_secret_refused";

    private static final String PATH = "/v1/tax/registrations";
    private static final Set<Integer> RELAYED = Set.of(400, 404, 409, 422);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String secret;
    private final @Nullable Counter secretRefused;

    /**
     * The client Spring builds: its own request factory with the configured connect and read timeouts, so a slow
     * pos-tax answers 503 instead of holding the request thread.
     */
    @Autowired
    public TaxRegistrationClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${pos.accounting.tax.base-url:http://pos-tax:8091}") String baseUrl,
            @Value("${pos.accounting.tax.front-door-secret:}") String secret,
            @Value("${pos.accounting.tax.connect-timeout:2s}") Duration connectTimeout,
            @Value("${pos.accounting.tax.read-timeout:5s}") Duration readTimeout,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this(
                restClientBuilder.clone().requestFactory(requestFactory(connectTimeout, readTimeout)),
                objectMapper,
                baseUrl,
                secret,
                meterRegistry.getIfAvailable());
    }

    /** A client on a builder whose request factory the caller has set (tests bind a mock server to it). */
    TaxRegistrationClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            String baseUrl,
            String secret,
            @Nullable MeterRegistry meterRegistry) {
        this.restClient = restClientBuilder.clone().baseUrl(baseUrl).build();
        this.secretRefused = meterRegistry == null
                ? null
                : Counter.builder(SECRET_REFUSED_COUNTER)
                        .description("pos-tax refused this service's front-door secret (401)")
                        .register(meterRegistry);
        this.objectMapper = objectMapper;
        this.secret = secret == null ? "" : secret;
        if (this.secret.isBlank()) {
            log.warn("pos.accounting.tax.front-door-secret is not set: every tax-registration write answers 503");
        }
    }

    /**
     * Records a registration in pos-tax.
     *
     * @param request the body, already checked by the front door
     * @param actor   the {@code X-User-Id} the gateway forwarded
     * @return pos-tax's answer, {@code replayed} on a 200
     */
    public @NonNull Written create(@NonNull RecordTaxRegistrationRequest request, @NonNull String actor) {
        return call(() -> restClient.post().uri(PATH), request, actor);
    }

    /**
     * Changes a registration in pos-tax.
     *
     * @param registrationId the registration
     * @param request        the body, already checked by the front door
     * @param actor          the {@code X-User-Id} the gateway forwarded
     * @return pos-tax's answer
     */
    public @NonNull Written update(
            @NonNull UUID registrationId, @NonNull ChangeTaxRegistrationRequest request, @NonNull String actor) {
        return call(() -> restClient.put().uri(PATH + "/{registrationId}", registrationId), request, actor);
    }

    static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    /** The inbound request's correlation id, forwarded so pos-tax logs under the same id (ADR-0017 §4). */
    private static @Nullable String correlationId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String header = attributes.getRequest().getHeader(CORRELATION_HEADER);
            return header == null || header.isBlank() ? null : header.trim();
        }
        return null;
    }

    private Written call(Supplier<RestClient.RequestBodySpec> spec, Object body, String actor) {
        if (secret.isBlank()) {
            throw new TaxServiceUnavailableException("The tax registry cannot be reached from accounting");
        }
        UUID tenant = TenantContext.require();
        try {
            RestClient.RequestBodySpec request = spec.get();
            String correlationId = correlationId();
            if (correlationId != null) {
                request.header(CORRELATION_HEADER, correlationId);
            }
            var entity = request.contentType(MediaType.APPLICATION_JSON)
                    .header(SECRET_HEADER, secret)
                    .header(ACTOR_HEADER, actor)
                    .header(TenantHeaders.HTTP_TENANT_ID, tenant.toString())
                    .body(body)
                    .retrieve()
                    .toEntity(Registration.class);
            Registration registration = entity.getBody();
            if (registration == null) {
                throw new TaxServiceUnavailableException("The tax registry returned no registration");
            }
            return new Written(registration, entity.getStatusCode().value() == 200);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (RELAYED.contains(status)) {
                throw new TaxRegistrationRelayException(status, readError(e, status));
            }
            if (status == 401 && secretRefused != null) {
                secretRefused.increment();
            }
            log.error("pos-tax answered {} to a tax-registration write; answering 503", status);
            throw new TaxServiceUnavailableException("The tax registry is unavailable");
        } catch (ResourceAccessException e) {
            log.warn(
                    "pos-tax is unreachable for a tax-registration write: {}",
                    e.getClass().getSimpleName());
            throw new TaxServiceUnavailableException("The tax registry is unavailable");
        } catch (RestClientException e) {
            log.warn(
                    "pos-tax's answer to a tax-registration write was unreadable: {}",
                    e.getClass().getSimpleName());
            throw new TaxServiceUnavailableException("The tax registry is unavailable");
        }
    }

    private ApiError readError(RestClientResponseException e, int status) {
        try {
            ApiError error = objectMapper.readValue(e.getResponseBodyAsByteArray(), ApiError.class);
            if (error != null && error.code() != null) {
                return error;
            }
        } catch (RuntimeException parse) {
            log.warn("pos-tax's {} carried no readable error envelope", status);
        }
        throw new TaxServiceUnavailableException("The tax registry is unavailable");
    }

    /** pos-tax's registration as it answered a write. */
    public record Registration(
            @NonNull UUID registrationId,
            @NonNull String countryCode,
            @NonNull String regime,
            @NonNull String registrationNumber,
            @NonNull String jurisdictionCode,
            @NonNull LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo,
            @NonNull String status,
            long version,
            @Nullable Instant updatedAt) {

        @Override
        public @NonNull String toString() {
            return "Registration[registrationId=" + registrationId + ", regime=" + regime + ", version=" + version
                    + "]";
        }
    }

    /**
     * A write's outcome.
     *
     * @param registration pos-tax's registration
     * @param replayed     whether pos-tax answered a replayed request id (200 on a create)
     */
    public record Written(@NonNull Registration registration, boolean replayed) {}
}
