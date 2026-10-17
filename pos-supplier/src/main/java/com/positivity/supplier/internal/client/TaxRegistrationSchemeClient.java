package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.exception.SupplierServiceUnavailableException;
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
 */
@Slf4j
@Component
public class TaxRegistrationSchemeClient {

    /** The authority pos-tax requires of the read. */
    static final String TAX_RATES_VIEW = "tax:rates:view";

    static final String SERVICE_USER = "pos-supplier-service";

    private final RestClient restClient;
    private final String path;

    public TaxRegistrationSchemeClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.tax.service-id:pos-tax}") String serviceId,
            @Value("${pos.tax.registration-schemes-path:/v1/tax/registration-schemes}") String path) {
        this.path = path;
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    /**
     * The schemes, every country's or one country's.
     *
     * @param countryCode an upper-case ISO 3166-1 alpha-2 code, or {@code null} for every configured country
     * @return pos-tax's answer, unchanged
     * @throws SupplierServiceUnavailableException when pos-tax cannot be reached or does not answer the list
     */
    public @NonNull TaxRegistrationSchemes fetch(@Nullable String countryCode) {
        try {
            TaxRegistrationSchemes schemes = restClient
                    .get()
                    .uri(builder -> {
                        builder.path(path);
                        if (countryCode != null) {
                            builder.queryParam("countryCode", countryCode);
                        }
                        return builder.build();
                    })
                    .header("X-User", SERVICE_USER)
                    .header("X-Authorities", TAX_RATES_VIEW)
                    .retrieve()
                    .body(TaxRegistrationSchemes.class);
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
