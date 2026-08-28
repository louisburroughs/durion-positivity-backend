package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.ProductCodeDuplicate;
import com.positivity.catalog.internal.dto.ProductCodeKind;
import com.positivity.catalog.internal.dto.ProductCodeMatch;
import com.positivity.catalog.internal.entity.ProductCodeType;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.repository.ProductRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic EAN/UPC product lookup backed by the per-type unique product-code index. */
@Service
@RequiredArgsConstructor
public class ProductCodeLookupServiceImpl implements ProductCodeLookupService {

    private final ProductRepository productRepository;

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public Optional<ProductCodeMatch> findByProductCode(@NonNull ProductCodeKind codeType, @NonNull String code) {
        String normalized = ProductCodeNormalizer.normalize(code);
        if (normalized == null) {
            return Optional.empty();
        }
        ProductCodeType entityType = ProductCodeNormalizer.toEntityType(codeType);
        List<ProductEntity> matches = productRepository.findByProductCodeTypeAndProductCode(entityType, normalized);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            // Only reachable on a schema where the uniqueness constraint has not been applied.
            // Guessing between candidates would be exactly the non-deterministic matching ADR-0053
            // forbids, so the caller is told the data is ambiguous instead.
            throw new CatalogBusinessRuleException("Product code " + normalized + " of type " + entityType
                    + " is carried by " + matches.size() + " products; resolve the duplicates before matching");
        }
        return Optional.of(toMatch(matches.getFirst(), codeType, normalized));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<ProductCodeDuplicate> findDuplicateProductCodes() {
        List<ProductCodeDuplicate> duplicates = new ArrayList<>();
        for (Object[] group : productRepository.findDuplicateProductCodeGroups()) {
            ProductCodeType codeType = (ProductCodeType) group[0];
            String code = (String) group[1];
            List<UUID> productIds = productRepository.findByProductCodeTypeAndProductCode(codeType, code).stream()
                    .map(ProductEntity::getId)
                    .sorted(Comparator.naturalOrder())
                    .toList();
            duplicates.add(new ProductCodeDuplicate(ProductCodeNormalizer.toKind(codeType), code, productIds));
        }
        return List.copyOf(duplicates);
    }

    private ProductCodeMatch toMatch(ProductEntity product, ProductCodeKind codeType, String code) {
        return new ProductCodeMatch(
                product.getId(),
                product.getSku(),
                product.getName(),
                codeType,
                code,
                product.getManufacturerId(),
                product.getManufacturerPartNumber());
    }
}
