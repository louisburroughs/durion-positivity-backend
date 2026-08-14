package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.audit.SupplierCorrelationContext;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls {@code GET /v1/products/by-code} on pos-catalog (#1232) to resolve PRICAT lines
 * (ADR-0053 §5).
 *
 * <p>Cross-module access is REST, never a shared table: pos-supplier owns received documents,
 * pos-catalog owns product identity, and matching is a question one asks the owner.
 *
 * <p>Status handling is exhaustive by design. 404 is a genuine miss, 409 is the ambiguity that
 * #1232's uniqueness constraint exists to eliminate, and everything else — 5xx, a timeout, an auth
 * failure — is {@code Unavailable}, because a line the catalog could not be asked about is not a
 * line the catalog rejected. That distinction is what lets those lines be re-applied later without
 * asking the vendor for the document again.
 */
@Slf4j
@Component
public class CatalogProductLookupClientImpl implements CatalogProductLookupClient {

    private final RestClient restClient;
    private final String basePath;

    public CatalogProductLookupClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.catalog.service-id:catalog}") String serviceId,
            @Value("${pos.catalog.base-path:/v1/products}") String basePath) {
        this.basePath = basePath;
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    @Override
    @NonNull
    public LookupResult findProductByCode(@NonNull String codeType, @NonNull String code) {
        try {
            ProductCodeMatchResponse response = restClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path(basePath + "/by-code")
                            .queryParam("codeType", codeType)
                            .queryParam("code", code)
                            .build())
                    .header("X-User", "pos-supplier-service")
                    .header("X-Authorities", "ROLE_CATALOG_VIEW")
                    .header(
                            SupplierCorrelationContext.CORRELATION_HEADER,
                            SupplierCorrelationContext.currentOrGenerate())
                    .retrieve()
                    // Error statuses raise RestClientResponseException, which carries the status
                    // code; the catch below turns it into the right outcome rather than collapsing
                    // a 404 miss and a 503 outage into one "no match".
                    .body(ProductCodeMatchResponse.class);
            if (response == null || response.productId() == null) {
                return new LookupResult.NotFound();
            }
            return new LookupResult.Matched(response.productId());
        } catch (org.springframework.web.client.RestClientResponseException e) {
            return fromStatus(e.getStatusCode().value(), codeType, code);
        } catch (Exception e) {
            log.warn("Catalog lookup unavailable for {} {}: {}", codeType, code, e.toString());
            return new LookupResult.Unavailable(e.getClass().getSimpleName());
        }
    }

    private LookupResult fromStatus(int status, String codeType, String code) {
        if (status == 404) {
            return new LookupResult.NotFound();
        }
        if (status == 409) {
            return new LookupResult.Ambiguous("catalog reports more than one product for " + codeType + " " + code);
        }
        log.warn("Catalog lookup for {} {} returned status {}", codeType, code, status);
        return new LookupResult.Unavailable("catalog returned status " + status);
    }

    /** Mirrors the pos-catalog {@code ProductCodeMatch} contract; only the id is consumed here. */
    private record ProductCodeMatchResponse(UUID productId) {}
}
