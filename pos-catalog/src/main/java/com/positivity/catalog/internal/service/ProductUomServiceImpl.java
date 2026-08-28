package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.config.CatalogFactPublisher;
import com.positivity.catalog.internal.dto.ProductUomCreateRequestDto;
import com.positivity.catalog.internal.dto.ProductUomDto;
import com.positivity.catalog.internal.dto.ProductUomUpdateRequestDto;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ProductUomEntity;
import com.positivity.catalog.internal.entity.ProductUomType;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.internal.repository.ProductUomRepository;
import com.positivity.domainevents.AggregateTouch;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductUomServiceImpl implements ProductUomService {

    private final ProductUomRepository productUomRepository;
    private final ProductRepository productRepository;
    private final CatalogFactPublisher catalogFactPublisher;
    private final Clock clock;

    @Override
    @Transactional
    public ProductUomDto addProductUom(@NonNull UUID productId, @NonNull ProductUomCreateRequestDto request) {
        ProductEntity product = findProduct(productId);
        String uomCode = normalize(request.getUomCode());
        if (productUomRepository.existsByProductIdAndUomCode(productId, uomCode)) {
            throw new CatalogBusinessRuleException("Product UoM already exists for code " + uomCode);
        }
        validateFactor(request.getUomType(), request.getFactorToBase());

        ProductUomEntity entity = new ProductUomEntity();
        entity.setProduct(product);
        entity.setUomCode(uomCode);
        entity.setUomType(request.getUomType());
        entity.setFactorToBase(request.getFactorToBase().setScale(6, RoundingMode.HALF_UP));
        entity.setPrecisionScale(request.getPrecisionScale());
        ProductUomEntity saved = productUomRepository.save(entity);

        touchAndPublish(product);
        return toDto(saved, productId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductUomDto> listProductUoms(@NonNull UUID productId) {
        findProduct(productId);
        return productUomRepository.findByProductIdOrderByUomCodeAsc(productId).stream()
                .map(entity -> toDto(entity, productId))
                .toList();
    }

    @Override
    @Transactional
    public ProductUomDto updateProductUom(
            @NonNull UUID productId, @NonNull UUID uomId, @NonNull ProductUomUpdateRequestDto request) {
        ProductEntity product = findProduct(productId);
        ProductUomEntity entity = findProductUom(productId, uomId);
        ProductUomType uomType = request.getUomType() == null ? entity.getUomType() : request.getUomType();
        validateFactor(uomType, request.getFactorToBase());

        entity.setUomType(uomType);
        entity.setFactorToBase(request.getFactorToBase().setScale(6, RoundingMode.HALF_UP));
        entity.setPrecisionScale(request.getPrecisionScale());
        ProductUomEntity saved = productUomRepository.save(entity);

        touchAndPublish(product);
        return toDto(saved, productId);
    }

    @Override
    @Transactional
    public void deleteProductUom(@NonNull UUID productId, @NonNull UUID uomId) {
        ProductEntity product = findProduct(productId);
        ProductUomEntity entity = findProductUom(productId, uomId);
        productUomRepository.delete(entity);
        productUomRepository.flush();
        touchAndPublish(product);
    }

    private void validateFactor(ProductUomType uomType, BigDecimal factor) {
        if (factor == null || factor.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CatalogValidationException("factorToBase must be greater than zero");
        }
        if (uomType == ProductUomType.BASE && factor.compareTo(BigDecimal.ONE) != 0) {
            throw new CatalogValidationException("BASE UoM requires factorToBase 1");
        }
    }

    /**
     * Bump the product row's {@code updatedAt} before re-emitting its fact: the mutation must dirty
     * the row for the envelope's {@code aggregateVersion} — the product's JPA {@code @Version}
     * (#1486) — to actually advance when {@link CatalogFactPublisher} flushes, so attribute-only
     * changes still advance it for consumers' stale-event guards.
     */
    private void touchAndPublish(ProductEntity product) {
        product.setUpdatedAt(AggregateTouch.monotonicUpdatedAt(product.getUpdatedAt(), clock));
        productRepository.saveAndFlush(product);
        catalogFactPublisher.publishProductUpdated(product);
    }

    private ProductEntity findProduct(UUID productId) {
        return productRepository
                .findById(productId)
                .orElseThrow(() -> new CatalogNotFoundException("Product not found: " + productId));
    }

    private ProductUomEntity findProductUom(UUID productId, UUID uomId) {
        ProductUomEntity entity = productUomRepository
                .findById(uomId)
                .orElseThrow(() -> new CatalogNotFoundException("Product UoM not found: " + uomId));
        if (!productId.equals(entity.getProduct().getId())) {
            throw new CatalogNotFoundException("Product UoM " + uomId + " does not belong to product " + productId);
        }
        return entity;
    }

    private String normalize(String code) {
        if (code == null || code.isBlank()) {
            throw new CatalogValidationException("uomCode is required");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private ProductUomDto toDto(ProductUomEntity entity, UUID productId) {
        return ProductUomDto.builder()
                .id(entity.getId())
                .productId(productId)
                .uomCode(entity.getUomCode())
                .uomType(entity.getUomType())
                .factorToBase(entity.getFactorToBase())
                .precisionScale(entity.getPrecisionScale())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
