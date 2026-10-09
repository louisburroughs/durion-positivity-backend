package com.positivity.accounting.internal.client;

import com.positivity.accounting.internal.dto.InformationReturnFormsResponse;
import com.positivity.accounting.internal.exception.TaxReferenceRelayException;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.shared.error.ApiError;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantHeaders;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 * pos-accounting's utility client for pos-tax's configured references (CAP:550 #2615; ADR-0044 R2): today the
 * information-return forms of a country. S43 (#2604) adds its own reads here.
 *
 * <p>pos-tax is internal-only and not on Eureka, so it is reached on a fixed base URL ({@code pos.accounting.tax.base-url},
 * the S32c precedent) with bounded connect and read timeouts (defaults 2 s and 5 s). Each call is a service call:
 * {@code X-User: pos-accounting} and {@code X-Authorities: tax:rates:view}, with the bound tenant ({@code
 * X-Tenant-Id}) and the inbound {@code X-Correlation-Id} forwarded.
 *
 * <p>A pos-tax 400, 404 or 422 is relayed unchanged ({@link TaxReferenceRelayException}). Anything else that is not
 * an answer is 503 {@code SERVICE_UNAVAILABLE} ({@link TaxServiceUnavailableException}): pos-tax unreachable, a 5xx, a
 * 401 or 403 (this service's identity), or an unreadable body. Nothing logs a response body.
 */
@Slf4j
@Component
public class TaxReferenceClient {

    /** The service identity and authority of every call. */
    public static final String SERVICE_USER = "pos-accounting";

    public static final String SERVICE_AUTHORITY = "tax:rates:view";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    static final String INFORMATION_RETURN_FORMS = "/v1/tax/information-return-forms";

    /** pos-tax refusals of the request itself, relayed; a 401 or 403 (this service's identity) is 503 instead. */
    private static final Set<Integer> RELAYED = Set.of(400, 404, 422);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /** The client Spring builds: its own request factory with the configured connect and read timeouts. */
    @Autowired
    public TaxReferenceClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${pos.accounting.tax.base-url:http://pos-tax:8091}") String baseUrl,
            @Value("${pos.accounting.tax.connect-timeout:2s}") Duration connectTimeout,
            @Value("${pos.accounting.tax.read-timeout:5s}") Duration readTimeout) {
        this(
                restClientBuilder.clone().requestFactory(requestFactory(connectTimeout, readTimeout)),
                objectMapper,
                baseUrl);
    }

    /** A client on a builder whose request factory the caller has set (tests bind a mock server to it). */
    TaxReferenceClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper, String baseUrl) {
        this.restClient = restClientBuilder.clone().baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    /**
     * The information-return forms pos-tax configures for {@code countryCode}.
     *
     * @param countryCode an upper-case ISO 3166-1 alpha-2 code
     * @return the forms; empty when the country configures none
     */
    public @NonNull InformationReturnFormsResponse informationReturnForms(@NonNull String countryCode) {
        try {
            RestClient.RequestHeadersSpec<?> request = restClient
                    .get()
                    .uri(uri -> uri.path(INFORMATION_RETURN_FORMS)
                            .queryParam("countryCode", countryCode)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-User", SERVICE_USER)
                    .header("X-Authorities", SERVICE_AUTHORITY);
            Optional<UUID> tenant = TenantContext.current();
            tenant.ifPresent(id -> request.header(TenantHeaders.HTTP_TENANT_ID, id.toString()));
            String correlationId = correlationId();
            if (correlationId != null) {
                request.header(CORRELATION_HEADER, correlationId);
            }
            InformationReturnFormsResponse forms = request.retrieve().body(InformationReturnFormsResponse.class);
            if (forms == null) {
                throw new TaxServiceUnavailableException("The tax service returned no information-return forms");
            }
            return forms;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (RELAYED.contains(status)) {
                throw new TaxReferenceRelayException(status, readError(e, status));
            }
            log.error("pos-tax answered {} to an information-return forms read; answering 503", status);
            throw new TaxServiceUnavailableException("The tax service is unavailable");
        } catch (ResourceAccessException e) {
            log.warn(
                    "pos-tax is unreachable for an information-return forms read: {}",
                    e.getClass().getSimpleName());
            throw new TaxServiceUnavailableException("The tax service is unavailable");
        } catch (RestClientException e) {
            log.warn(
                    "pos-tax's information-return forms answer was unreadable: {}",
                    e.getClass().getSimpleName());
            throw new TaxServiceUnavailableException("The tax service is unavailable");
        }
    }

    /** The inbound request's correlation id, forwarded so pos-tax logs under the same id (ADR-0017 §4). */
    private static @Nullable String correlationId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String header = attributes.getRequest().getHeader(CORRELATION_HEADER);
            return header == null || header.isBlank() ? null : header.trim();
        }
        return null;
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
        throw new TaxServiceUnavailableException("The tax service is unavailable");
    }
}
