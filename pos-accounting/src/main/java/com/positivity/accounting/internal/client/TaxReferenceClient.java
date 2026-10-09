package com.positivity.accounting.internal.client;

import com.positivity.accounting.internal.dto.InformationReturnFormsResponse;
import com.positivity.accounting.internal.dto.TaxPurchaseRules;
import com.positivity.accounting.internal.dto.TaxUseQuote;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.shared.error.ApiError;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantHeaders;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
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
 * information-return forms of a country, and (CAP:550 S43, #2604) its purchase-tax rules and the self-assessed
 * ({@code USE}) tax of a vendor bill.
 *
 * <p>pos-tax is internal-only and not on Eureka, so it is reached on a fixed base URL ({@code pos.accounting.tax.base-url},
 * the S32c precedent) with bounded connect and read timeouts (defaults 2 s and 5 s). Each call is a service call:
 * {@code X-User: pos-accounting} and {@code X-Authorities: tax:rates:view} ({@code tax:calculate} for the use-tax
 * calculation), with the bound tenant ({@code
 * X-Tenant-Id}) and the inbound {@code X-Correlation-Id} forwarded.
 *
 * <p>Anything that is not an answer is 503 {@code SERVICE_UNAVAILABLE} with {@code Retry-After} ({@link
 * TaxServiceUnavailableException}): pos-tax unreachable, any 4xx or 5xx, or an unreadable body. A 4xx is not relayed:
 * the country is the server's own setting, so it is never the caller's fault (ADR-0017); it is logged at WARN with
 * pos-tax's status and code only. Nothing logs a response body.
 */
@Slf4j
@Component
public class TaxReferenceClient {

    /** The service identity and authority of every call. */
    public static final String SERVICE_USER = "pos-accounting";

    public static final String SERVICE_AUTHORITY = "tax:rates:view";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    /** The authority of the use-tax calculation (CAP:550 S43). */
    public static final String CALCULATE_AUTHORITY = "tax:calculate";

    static final String INFORMATION_RETURN_FORMS = "/v1/tax/information-return-forms";
    static final String PURCHASE_RULES = "/v1/tax/purchase-rules";
    static final String CALCULATE = "/v1/tax/calculate";

    /** The shape of an error code worth logging; anything else is logged as {@code "-"}. */
    private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

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
        return call(
                "an information-return forms read",
                () -> withHeaders(
                                restClient
                                        .get()
                                        .uri(uri -> uri.path(INFORMATION_RETURN_FORMS)
                                                .queryParam("countryCode", countryCode)
                                                .build())
                                        .accept(MediaType.APPLICATION_JSON),
                                SERVICE_AUTHORITY)
                        .retrieve()
                        .body(InformationReturnFormsResponse.class));
    }

    /**
     * The purchase-tax rules pos-tax configures for {@code countryCode} on {@code asOf} (CAP:550 S43, AW44). A country
     * without rules answers {@code configured = false}: a defined answer. Anything that is not an answer is 503 (AW49:
     * never read as "off").
     *
     * @param countryCode an upper-case ISO 3166-1 alpha-2 code (the tax country)
     * @param asOf the decision's posting date, or the read's
     * @return the rules
     */
    public @NonNull TaxPurchaseRules purchaseRules(@NonNull String countryCode, @NonNull LocalDate asOf) {
        return call(
                "a purchase-tax rules read",
                () -> withHeaders(
                                restClient
                                        .get()
                                        .uri(uri -> uri.path(PURCHASE_RULES)
                                                .queryParam("countryCode", countryCode)
                                                .queryParam("asOf", asOf.toString())
                                                .build())
                                        .accept(MediaType.APPLICATION_JSON),
                                SERVICE_AUTHORITY)
                        .retrieve()
                        .body(TaxPurchaseRules.class));
    }

    /**
     * The self-assessed (use) tax of {@code request}'s lines (CAP:550 S43, AW44): {@code POST /v1/tax/calculate} with
     * {@code calculationType = USE}, under the authority {@value #CALCULATE_AUTHORITY}. Every value of the request is
     * the server's own (the configured country and place, the ledger currency, the bill), so no pos-tax refusal is the
     * caller's fault: any 4xx, 5xx or 501 is 503, never relayed (ADR-0017).
     *
     * @param request the quote request
     * @return pos-tax's answer, amounts as returned
     */
    public TaxUseQuote.@NonNull Response useTax(TaxUseQuote.@NonNull Request request) {
        return call(
                "a use-tax calculation",
                () -> withHeaders(
                                restClient
                                        .post()
                                        .uri(CALCULATE)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .accept(MediaType.APPLICATION_JSON)
                                        .body(request),
                                CALCULATE_AUTHORITY)
                        .retrieve()
                        .body(TaxUseQuote.Response.class));
    }

    /** The service identity, the authority, the bound tenant and the inbound correlation id. */
    private static <S extends RestClient.RequestHeadersSpec<?>> S withHeaders(S request, String authority) {
        request.header("X-User", SERVICE_USER).header("X-Authorities", authority);
        Optional<UUID> tenant = TenantContext.current();
        tenant.ifPresent(id -> request.header(TenantHeaders.HTTP_TENANT_ID, id.toString()));
        String correlationId = correlationId();
        if (correlationId != null) {
            request.header(CORRELATION_HEADER, correlationId);
        }
        return request;
    }

    /**
     * Runs one pos-tax call: an empty body, any 4xx or 5xx, an unreachable pos-tax or an unreadable answer is 503
     * {@code SERVICE_UNAVAILABLE} with {@code Retry-After}. Only the status and pos-tax's code are logged, never a body
     * or a value.
     */
    private <T> @NonNull T call(String what, Supplier<@Nullable T> exchange) {
        try {
            T answer = exchange.get();
            if (answer == null) {
                throw new TaxServiceUnavailableException("The tax service returned no answer");
            }
            return answer;
        } catch (RestClientResponseException e) {
            // The request is the server's own (TaxCountry, its settings), so no pos-tax answer here is the caller's
            // fault: a 4xx (a rollout skew, a 404 before the stub is deployed, a refused service identity) is 503 like
            // a 5xx, never relayed (ADR-0017).
            int status = e.getStatusCode().value();
            if (e.getStatusCode().is4xxClientError()) {
                log.warn("pos-tax refused {}: status {}, code {}; answering 503", what, status, errorCode(e));
            } else {
                log.error("pos-tax answered {} to {}; answering 503", status, what);
            }
            throw new TaxServiceUnavailableException("The tax service is unavailable");
        } catch (ResourceAccessException e) {
            log.warn("pos-tax is unreachable for {}: {}", what, e.getClass().getSimpleName());
            throw new TaxServiceUnavailableException("The tax service is unavailable");
        } catch (RestClientException e) {
            log.warn(
                    "pos-tax's answer to {} was unreadable: {}",
                    what,
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

    /** pos-tax's error code when its envelope carries a well-formed one, else {@code "-"}; never anything else. */
    private String errorCode(RestClientResponseException e) {
        try {
            ApiError error = objectMapper.readValue(e.getResponseBodyAsByteArray(), ApiError.class);
            if (error != null
                    && error.code() != null
                    && ERROR_CODE.matcher(error.code()).matches()) {
                return error.code();
            }
        } catch (RuntimeException parse) {
            // No readable envelope: the status alone is logged.
        }
        return "-";
    }
}
