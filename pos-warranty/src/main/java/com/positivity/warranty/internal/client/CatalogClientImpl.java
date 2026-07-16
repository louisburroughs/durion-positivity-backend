package com.positivity.warranty.internal.client;

import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * RestClient implementation of {@link CatalogClient}.
 *
 * <p>pos-catalog product reads are guarded by {@code hasRole}, so the authorities
 * header must carry the full role name {@code ROLE_CATALOG_VIEW}.
 */
@Slf4j
@Component
public class CatalogClientImpl implements CatalogClient {

    private static final String SERVICE_USER = "pos-warranty-service";
    private static final String AUTHORITIES = "ROLE_CATALOG_VIEW";

    private final RestClient restClient;
    private final String basePath;

    public CatalogClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.catalog.service-id:catalog}") String serviceId,
            @Value("${pos.catalog.base-path:/v1/products}") String basePath) {
        this.basePath = basePath;
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    @Override
    public @NonNull Optional<ProductInfo> getProduct(@NonNull UUID productId) {
        try {
            ProductWire response = restClient
                    .get()
                    .uri(basePath + "/{productId}", productId)
                    .header("X-User", SERVICE_USER)
                    .header("X-Authorities", AUTHORITIES)
                    .retrieve()
                    .body(ProductWire.class);
            if (response == null) {
                return Optional.empty();
            }
            return Optional.of(new ProductInfo(
                    response.id(),
                    response.sku(),
                    response.name(),
                    response.manufacturerId(),
                    response.manufacturerName(),
                    response.manufacturerBrand(),
                    response.category() != null ? response.category().id() : null,
                    response.category() != null ? response.category().name() : null,
                    response.warranty(),
                    response.manufacturerWarranty()));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                log.debug("Product {} not found in catalog", productId);
            } else {
                log.warn(
                        "Catalog product lookup failed for productId={}: HTTP {}",
                        productId,
                        ex.getStatusCode().value());
            }
            return Optional.empty();
        } catch (RestClientException ex) {
            log.warn("pos-catalog unreachable for productId={}: {}", productId, ex.getMessage());
            return Optional.empty();
        }
    }

    // -----------------------------------------------------------------------
    // Wire shape (mirrors the pos-catalog ProductDto fields warranty needs;
    // unknown properties ignored)
    // -----------------------------------------------------------------------

    private record ProductWire(
            UUID id,
            String sku,
            String name,
            UUID manufacturerId,
            String manufacturerName,
            String manufacturerBrand,
            CategoryWire category,
            String warranty,
            String manufacturerWarranty) {}

    /** Mirrors pos-catalog's CategoryDto ({@code {id, name}}). */
    private record CategoryWire(UUID id, String name) {}
}
