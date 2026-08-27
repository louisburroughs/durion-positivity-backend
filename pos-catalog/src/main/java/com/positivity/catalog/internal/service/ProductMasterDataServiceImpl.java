package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.config.CatalogFactPublisher;
import com.positivity.catalog.internal.dto.CategoryDto;
import com.positivity.catalog.internal.dto.ProductCreateRequestDto;
import com.positivity.catalog.internal.dto.ProductDto;
import com.positivity.catalog.internal.dto.ProductSearchResultDto;
import com.positivity.catalog.internal.dto.ProductTrackingLevelUpdateRequestDto;
import com.positivity.catalog.internal.dto.ProductUpdateRequestDto;
import com.positivity.catalog.internal.dto.SubcategoryDto;
import com.positivity.catalog.internal.entity.Category;
import com.positivity.catalog.internal.entity.ProductCodeType;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ProductStatus;
import com.positivity.catalog.internal.entity.Subcategory;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.CategoryRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.internal.repository.SubcategoryRepository;
import com.positivity.catalog.service.ProductMasterDataService;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductMasterDataServiceImpl implements ProductMasterDataService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final ProductDetailCacheInvalidationPublisher productDetailCacheInvalidationPublisher;
    private final CatalogFactPublisher catalogFactPublisher;
    private final ProductCodeUniquenessGuard productCodeUniquenessGuard;

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
        entity.setManufacturerName(request.getManufacturerName());
        entity.setManufacturerBrand(request.getManufacturerBrand());
        entity.setCountryOfOrigin(request.getCountryOfOrigin());
        entity.setType(request.getType());
        entity.setSku(request.getSku());
        entity.setManufacturerPartNumber(request.getMpn());
        String upc = ProductCodeNormalizer.normalize(request.getUpc());
        productCodeUniquenessGuard.assertUnique(ProductCodeType.UPC, upc, null);
        entity.setUpc(upc);
        entity.setProductCode(upc);
        entity.setProductCodeType(upc == null ? null : ProductCodeType.UPC);
        entity.setAttributes(request.getAttributes());
        entity.setSpecifications(request.getAttributes());
        entity.setStatus(ProductStatus.ACTIVE);
        Subcategory subcategory = resolveSubcategory(request.getSubcategoryId());
        entity.setSubcategory(subcategory);
        entity.setCategory(reconcileCategoryPair(resolveCategory(request.getCategoryId()), subcategory));

        ProductEntity saved = productRepository.save(entity);
        productDetailCacheInvalidationPublisher.invalidateProduct(saved.getId());
        catalogFactPublisher.publishProductUpdated(saved);
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
        String upc = ProductCodeNormalizer.normalize(request.getUpc());
        productCodeUniquenessGuard.assertUnique(ProductCodeType.UPC, upc, productId);
        entity.setUpc(upc);
        entity.setProductCode(upc);
        entity.setProductCodeType(upc == null ? null : ProductCodeType.UPC);
        entity.setAttributes(request.getAttributes());
        entity.setSpecifications(request.getAttributes());
        Subcategory subcategory = resolveSubcategory(request.getSubcategoryId());
        entity.setSubcategory(subcategory);
        entity.setCategory(reconcileCategoryPair(resolveCategory(request.getCategoryId()), subcategory));

        ProductEntity saved = productRepository.save(entity);
        productDetailCacheInvalidationPublisher.invalidateProduct(saved.getId());
        catalogFactPublisher.publishProductUpdated(saved);
        return toDto(saved);
    }

    @Override
    @Transactional
    public ProductDto changeProductStatus(@NonNull UUID productId, @NonNull ProductStatus newStatus) {
        ProductEntity entity = findProduct(productId);
        entity.setStatus(newStatus);
        ProductEntity saved = productRepository.save(entity);
        productDetailCacheInvalidationPublisher.invalidateProduct(saved.getId());
        catalogFactPublisher.publishProductUpdated(saved);
        return toDto(saved);
    }

    @Override
    @Transactional
    public ProductDto updateTrackingLevel(
            @NonNull UUID productId, @NonNull ProductTrackingLevelUpdateRequestDto request) {
        ProductEntity entity = findProduct(productId);
        entity.setTrackingLevel(request.getTrackingLevel());
        ProductEntity saved = productRepository.save(entity);
        productDetailCacheInvalidationPublisher.invalidateProduct(saved.getId());
        catalogFactPublisher.publishProductUpdated(saved);
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

    private Subcategory resolveSubcategory(UUID subcategoryId) {
        if (subcategoryId == null) {
            return null;
        }
        Optional<Subcategory> subcategory = subcategoryRepository.findById(subcategoryId);
        return subcategory.orElseThrow(() -> new CatalogValidationException("Subcategory not found: " + subcategoryId));
    }

    /**
     * Reconciles the category/subcategory pair a write request carries into the single category the product
     * should hold (issue #1536).
     *
     * <p>Since {@code subcategory.category_id} is NOT NULL, a product's category is a function of its
     * subcategory. A supplied category that disagrees with the subcategory's parent is therefore not a choice
     * between two valid values — it is a contradiction, and pos-inventory's putaway matcher, which resolves
     * SKU &gt; SUBCATEGORY &gt; CATEGORY &gt; ANY on each id independently, would route stock on it silently.
     * A supplied category that is absent is not a contradiction: the parent is derived, which also lets a
     * bulk-ingest row carry only {@code subcategoryName}.
     *
     * @param category the requested category, or {@code null} when the request omitted it
     * @param subcategory the resolved subcategory, or {@code null} when the request omitted it
     * @return the category to persist, or {@code null} when the product is deliberately unclassified
     * @throws CatalogBusinessRuleException when both are supplied but the subcategory is not a child of the
     *     supplied category
     */
    private @Nullable Category reconcileCategoryPair(@Nullable Category category, @Nullable Subcategory subcategory) {
        if (subcategory == null) {
            return category;
        }
        if (category == null) {
            return subcategory.getCategory();
        }
        if (Objects.equals(subcategory.getCategory().getId(), category.getId())) {
            return category;
        }
        throw new CatalogBusinessRuleException("Subcategory '" + subcategory.getName() + "' belongs to category '"
                + subcategory.getCategory().getName() + "' ("
                + subcategory.getCategory().getId() + "), not '"
                + category.getName() + "' (" + category.getId() + ")");
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
        dto.setTrackingLevel(entity.getTrackingLevel());
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
        if (entity.getSubcategory() != null) {
            SubcategoryDto subcategoryDto = new SubcategoryDto();
            subcategoryDto.setId(entity.getSubcategory().getId());
            subcategoryDto.setName(entity.getSubcategory().getName());
            dto.setSubcategory(subcategoryDto);
        }
        return dto;
    }
}
