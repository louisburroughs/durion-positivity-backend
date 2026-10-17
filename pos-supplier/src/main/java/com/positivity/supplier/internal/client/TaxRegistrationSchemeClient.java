package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.audit.SupplierCorrelationContext;
import com.positivity.supplier.internal.exception.SupplierServiceUnavailableException;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantHeaders;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Reads pos-tax's registration-schemes stub (CAP:550 #2623, AW48): the closed vocabulary a vendor's tax registration
 * is filed under. pos-tax is a Utility module under ADR-0044 R2 and may be called directly, with the service user and
 * the {@code tax:rates:view} authority (the {@link ImageStoreClient} precedent).
 *
 * <h2>Fails closed</h2>
 *
 * A registration is never stored unchecked: when pos-tax cannot be reached, answers an error or an empty body, the
 * caller gets {@link SupplierServiceUnavailableException} (503 {@code SERVICE_UNAVAILABLE}) and writes nothing. The
 * failure is logged at WARN with the path and status only (ADR-0072: no payload).
 *
 * <h2>Headers and a short cache</h2>
 *
 * The call carries the bound tenant ({@code X-Tenant-Id}, so pos-tax's tenant filter accepts it when enforcement is on)
 * and the correlation id, as pos-accounting's pos-tax caller does. The full list (the vendor entry's read) is cached
 * for {@code pos.tax.registration-schemes-cache-ttl} (default 60 s), so a vendor write does not depend on pos-tax
 * answering at that instant; the cache is only ever filled by a successful answer, so an empty cache still fails
 * closed, and it never outlives a configuration change by more than the TTL (#2623 rule 8).
 */
@Slf4j
@Component
public class TaxRegistrationSchemeClient {

    /** The authority pos-tax requires of the read. */
    static final String TAX_RATES_VIEW = "tax:rates:view";

    static final String SERVICE_USER = "pos-supplier-service";

    private final RestClient restClient;
    private final String path;
    private final Clock clock;
    private final Duration cacheTtl;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public TaxRegistrationSchemeClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.tax.service-id:pos-tax}") String serviceId,
            @Value("${pos.tax.registration-schemes-path:/v1/tax/registration-schemes}") String path,
            @Value("${pos.tax.registration-schemes-cache-ttl:PT60S}") Duration cacheTtl,
            Clock clock) {
        this.path = path;
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
        this.cacheTtl = cacheTtl;
        this.clock = clock;
    }

    private record Cached(TaxRegistrationSchemes schemes, Instant expiresAt) {}

    /**
     * The schemes, every country's or one country's.
     *
     * @param countryCode an upper-case ISO 3166-1 alpha-2 code, or {@code null} for every configured country
     * @return pos-tax's answer, unchanged
     * @throws SupplierServiceUnavailableException when pos-tax cannot be reached or does not answer the list
     */
    public @NonNull TaxRegistrationSchemes fetch(@Nullable String countryCode) {
        if (countryCode == null) {
            Cached held = cache.get();
            if (held != null && held.expiresAt().isAfter(Instant.now(clock))) {
                return held.schemes();
            }
        }
        TaxRegistrationSchemes schemes = read(countryCode);
        if (countryCode == null && !cacheTtl.isZero() && !cacheTtl.isNegative()) {
            cache.set(new Cached(schemes, Instant.now(clock).plus(cacheTtl)));
        }
        return schemes;
    }

    private TaxRegistrationSchemes read(@Nullable String countryCode) {
        try {
            RestClient.RequestHeadersSpec<?> request = restClient
                    .get()
                    .uri(builder -> {
                        builder.path(path);
                        if (countryCode != null) {
                            builder.queryParam("countryCode", countryCode);
                        }
                        return builder.build();
                    })
                    .header("X-User", SERVICE_USER)
                    .header("X-Authorities", TAX_RATES_VIEW);
            Optional<UUID> tenant = TenantContext.current();
            tenant.ifPresent(id -> request.header(TenantHeaders.HTTP_TENANT_ID, id.toString()));
            String correlationId = SupplierCorrelationContext.current().orElse(null);
            if (correlationId != null) {
                request.header(SupplierCorrelationContext.CORRELATION_HEADER, correlationId);
            }
            TaxRegistrationSchemes schemes = request.retrieve().body(TaxRegistrationSchemes.class);
            if (schemes == null || schemes.schemes() == null) {
                log.warn("pos-tax registration schemes unavailable | path={} | status=empty-body", path);
                throw new SupplierServiceUnavailableException("pos-tax answered no registration-scheme list");
            }
            return schemes;
        } catch (RestClientResponseException e) {
            log.warn(
                    "pos-tax registration schemes unavailable | path={} | status={}",
                    path,
                    e.getStatusCode().value());
            throw new SupplierServiceUnavailableException(
                    "pos-tax answered " + e.getStatusCode().value() + " to the registration-scheme read", e);
        } catch (RestClientException e) {
            log.warn("pos-tax registration schemes unavailable | path={} | status=unreachable", path);
            throw new SupplierServiceUnavailableException(
                    "pos-tax could not be reached for the registration schemes", e);
        }
    }
}
