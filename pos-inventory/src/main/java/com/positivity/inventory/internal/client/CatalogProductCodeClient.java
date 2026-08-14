package com.positivity.inventory.internal.client;

import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Resolves an article code to a catalog product through pos-catalog's exact-match lookup
 * (CAP-322, #1312; {@code GET /v1/products/by-code}, ADR-0053 §5).
 *
 * <p>pos-inventory holds no product-code data of its own — no EAN, no UPC, nothing a vendor's
 * article identifier could be matched against locally — so resolution has to be asked of the domain
 * that owns product identity. The lookup is deterministic and exact: at most one product can carry
 * a given code under a given scheme, a miss returns 404, and an ambiguous code is refused with 409
 * rather than guessed at. None of those outcomes is an error here; an unresolved hint is a normal,
 * retained state.
 *
 * <p>Matching an EAN asserts only <em>which article this is</em>. It does not treat the vendor's
 * report as a commercial commitment, which is the thing the producer warns against carrying over
 * from PRICAT matching rules.
 *
 * <p>Consumer-side DTO by design (ADR-0016): pos-catalog's own response type is not imported.
 */
@Slf4j
@Component
public class CatalogProductCodeClient {

    private final RestClient restClient;
    private final String basePath;

    public CatalogProductCodeClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.catalog.service-id:catalog}") String serviceId,
            @Value("${pos.catalog.base-path:/v1/products}") String basePath) {
        this.basePath = basePath;
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    /**
     * The single product carrying this code, if catalog carries it at all.
     *
     * @param codeType code scheme to match within, {@code EAN} or {@code UPC}
     * @param code     exact code value
     * @return the match, or empty when nothing matched or the code is carried by several products
     * @throws org.springframework.web.client.RestClientException when catalog could not be reached
     *                                                            or answered with a server error —
     *                                                            the caller leaves the hint pending
     *                                                            and retries on a later pass
     */
    @NonNull
    public Optional<ProductCodeMatchDto> findByCode(@NonNull String codeType, @NonNull String code) {
        ProductCodeMatchDto match = restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path(basePath + "/by-code")
                        .queryParam("codeType", codeType)
                        .queryParam("code", code)
                        .build())
                .header("X-User", "pos-inventory-service")
                .header("X-Authorities", "ROLE_CATALOG_VIEW")
                .retrieve()
                // A miss is the expected outcome of a lookup and has no body.
                .onStatus(status -> status.value() == 404, (request, response) -> {})
                // Catalog refuses to arbitrate between products carrying the same code, and so do
                // we: an ambiguous article stays unresolved rather than resolving to a guess.
                .onStatus(
                        status -> status.value() == 409,
                        (request, response) ->
                                log.warn("Catalog reports code {}={} carried by more than one product", codeType, code))
                .body(ProductCodeMatchDto.class);
        return Optional.ofNullable(match).filter(dto -> dto.productId() != null);
    }

    /** Consumer-side view of a catalog product-code match; only the fields resolution needs. */
    public record ProductCodeMatchDto(UUID productId, String sku, String name, String codeType, String code) {}
}
