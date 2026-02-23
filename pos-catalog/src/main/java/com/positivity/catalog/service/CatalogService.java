package com.positivity.catalog.service;

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

    Optional<NonInventoryProductDto> getNonInventoryProductById(UUID productId);

    List<NonInventoryProductDto> getNonInventoryProductsByName(String name);

    Optional<CatalogDto> getCatalogById(UUID catalogId);

    List<CatalogDto> getCatalogsByName(String name);

    CatalogItemResponseDto addCatalogItem(String type, CatalogItemRequestDto request);

    Optional<CatalogItemResponseDto> updateCatalogItem(String type, UUID catalogId, CatalogItemRequestDto request);

    boolean deleteCatalogItem(String type, UUID catalogId);

    CatalogDto addCatalog(CatalogDto request);

    Optional<CatalogDto> updateCatalog(UUID catalogId, CatalogDto request);

    boolean deleteCatalog(UUID catalogId);
}
