package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.CategoryDto;
import com.positivity.catalog.internal.dto.ProductCreateRequestDto;
import com.positivity.catalog.internal.dto.ProductDto;
import com.positivity.catalog.internal.dto.ProductSearchResultDto;
import com.positivity.catalog.internal.dto.ProductUpdateRequestDto;
import com.positivity.catalog.internal.entity.Category;
import com.positivity.catalog.internal.entity.ProductCodeType;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ProductStatus;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.CategoryRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.service.ProductMasterDataService;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductMasterDataServiceImpl implements ProductMasterDataService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductDetailCacheInvalidationPublisher productDetailCacheInvalidationPublisher;

    @Override
    @Transactional
    public ProductDto createProduct(@NonNull ProductCreateRequestDto request) {
        validateCreateUniqueness(request);

        ProductEntity entity = new ProductEntity();
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setShortDescription(request.getDescription());
        entity.setLongDescription(request.getDescription());
        entity.setUnitOfMeasure(request.getUnitOfMeasure());
        entity.setManufacturerId(request.getManufacturerId());
        entity.setSku(request.getSku());
        entity.setManufacturerPartNumber(request.getMpn());
        entity.setUpc(request.getUpc());
        entity.setProductCode(request.getUpc());
        entity.setProductCodeType(request.getUpc() == null ? null : ProductCodeType.UPC);
        entity.setAttributes(request.getAttributes());
        entity.setSpecifications(request.getAttributes());
        entity.setStatus(ProductStatus.ACTIVE);
        entity.setCategory(resolveCategory(request.getCategoryId()));

        ProductEntity saved = productRepository.save(entity);
        productDetailCacheInvalidationPublisher.invalidateProduct(saved.getId());
        return toDto(saved);
    }

    @Override
    @Transactional
    public ProductDto updateProduct(@NonNull UUID productId, @NonNull ProductUpdateRequestDto request) {
        ProductEntity entity = findProduct(productId);

        if (request.getSku() != null && !request.getSku().equalsIgnoreCase(entity.getSku())) {
            throw new CatalogValidationException("sku is immutable and cannot be changed");
        }

        if (request.getManufacturerId() != null
                && request.getMpn() != null
                && productRepository.existsByManufacturerIdAndManufacturerPartNumberIgnoreCaseAndIdNot(
                        request.getManufacturerId(), request.getMpn(), productId)) {
            throw new CatalogBusinessRuleException("Product with manufacturerId + mpn already exists");
        }

        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setShortDescription(request.getDescription());
        entity.setLongDescription(request.getDescription());
        entity.setUnitOfMeasure(request.getUnitOfMeasure());
        entity.setManufacturerId(request.getManufacturerId());
        entity.setManufacturerPartNumber(request.getMpn());
        entity.setUpc(request.getUpc());
        entity.setProductCode(request.getUpc());
        entity.setProductCodeType(request.getUpc() == null ? null : ProductCodeType.UPC);
        entity.setAttributes(request.getAttributes());
        entity.setSpecifications(request.getAttributes());
        entity.setCategory(resolveCategory(request.getCategoryId()));

        ProductEntity saved = productRepository.save(entity);
        productDetailCacheInvalidationPublisher.invalidateProduct(saved.getId());
        return toDto(saved);
    }

    @Override
    @Transactional
    public ProductDto changeProductStatus(@NonNull UUID productId, @NonNull ProductStatus newStatus) {
        ProductEntity entity = findProduct(productId);
        entity.setStatus(newStatus);
        ProductEntity saved = productRepository.save(entity);
        productDetailCacheInvalidationPublisher.invalidateProduct(saved.getId());
        return toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductSearchResultDto searchProducts(String q, String sku, String mpn, @NonNull Pageable pageable) {
        Page<ProductEntity> page = productRepository.searchProducts(q, sku, mpn, pageable);
        return ProductSearchResultDto.builder()
                .items(page.map(this::toDto).toList())
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .total(page.getTotalElements())
                .build();
    }

    private void validateCreateUniqueness(ProductCreateRequestDto request) {
        if (productRepository.existsBySkuIgnoreCase(request.getSku())) {
            throw new CatalogBusinessRuleException("Product with sku already exists");
        }
        if (request.getManufacturerId() != null
                && productRepository.existsByManufacturerIdAndManufacturerPartNumberIgnoreCase(
                        request.getManufacturerId(), request.getMpn())) {
            throw new CatalogBusinessRuleException("Product with manufacturerId + mpn already exists");
        }
    }

    private ProductEntity findProduct(UUID productId) {
        return productRepository
                .findById(productId)
                .orElseThrow(() -> new CatalogNotFoundException("Product not found: " + productId));
    }

    private Category resolveCategory(UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        Optional<Category> category = categoryRepository.findById(categoryId);
        return category.orElseThrow(() -> new CatalogValidationException("Category not found: " + categoryId));
    }

    private ProductDto toDto(ProductEntity entity) {
        ProductDto dto = new ProductDto();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setShortDescription(entity.getShortDescription());
        dto.setLongDescription(entity.getLongDescription());
        dto.setUnitOfMeasure(entity.getUnitOfMeasure());
        dto.setManufacturerId(entity.getManufacturerId());
        dto.setSku(entity.getSku());
        dto.setMpn(entity.getManufacturerPartNumber());
        dto.setUpc(entity.getUpc());
        dto.setAttributes(entity.getAttributes());
        dto.setStatus(entity.getStatus());
        dto.setLifecycleState(entity.getLifecycleState());
        dto.setLifecycleStateEffectiveAt(entity.getLifecycleStateEffectiveAt());
        dto.setLastStateChangedBy(entity.getLastStateChangedBy());
        dto.setLastStateChangedAt(entity.getLastStateChangedAt());
        dto.setLifecycleOverrideReason(entity.getLifecycleOverrideReason());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        if (entity.getCategory() != null) {
            CategoryDto categoryDto = new CategoryDto();
            categoryDto.setId(entity.getCategory().getId());
            categoryDto.setName(entity.getCategory().getName());
            dto.setCategory(categoryDto);
        }
        return dto;
    }
}
