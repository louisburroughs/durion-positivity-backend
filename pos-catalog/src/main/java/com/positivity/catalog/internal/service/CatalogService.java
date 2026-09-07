package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.CatalogDto;
import com.positivity.catalog.internal.dto.CatalogItemRequestDto;
import com.positivity.catalog.internal.dto.CatalogItemResponseDto;
import com.positivity.catalog.internal.dto.NonInventoryProductDto;
import com.positivity.catalog.internal.dto.ProductDto;
import com.positivity.catalog.internal.dto.ServiceDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogService {

    Optional<ProductDto> getProductById(UUID productId);

    List<ProductDto> getProductsByName(String name);

    Optional<ServiceDto> getServiceById(UUID serviceId);

    List<ServiceDto> getServicesByName(String name);

    List<ServiceDto> searchServices(String q, int limit);

    Optional<NonInventoryProductDto> getNonInventoryProductById(UUID productId);

    List<NonInventoryProductDto> getNonInventoryProductsByName(String name);

    Optional<CatalogDto> getCatalogById(UUID catalogId);

    List<CatalogDto> getCatalogsByName(String name);

    CatalogItemResponseDto addCatalogItem(String type, CatalogItemRequestDto request);

    /**
     * Creates or updates a service addressed by its Durion operation code, returning the row as it
     * now stands.
     *
     * <p>The bulk-ingest path needs a natural key rather than an id: a fixture file names an
     * operation by the code it is known by, and the ids are minted by whichever environment loads
     * it (docs/DATA_SEED_STRATEGY.md §5.3). Re-running a pack therefore converges on the same
     * rows instead of failing on the operation-code uniqueness rule.
     *
     * <p>Goes through the same publish as {@link #addCatalogItem}, so an ingested service
     * announces itself on the fact topic exactly as a hand-created one does — which is the whole
     * reason this data stopped being a Flyway seed.
     */
    CatalogItemResponseDto upsertServiceByOperationCode(CatalogItemRequestDto request);

    Optional<CatalogItemResponseDto> updateCatalogItem(String type, UUID catalogId, CatalogItemRequestDto request);

    boolean deleteCatalogItem(String type, UUID catalogId);

    CatalogDto addCatalog(CatalogDto request);

    Optional<CatalogDto> updateCatalog(UUID catalogId, CatalogDto request);

    boolean deleteCatalog(UUID catalogId);
}
