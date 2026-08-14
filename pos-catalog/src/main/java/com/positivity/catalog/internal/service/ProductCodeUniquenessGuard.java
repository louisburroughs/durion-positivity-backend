package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.entity.ProductCodeType;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.repository.ProductRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Enforces per-type product-code uniqueness on every write path, so that a violation is reported
 * as a business conflict with an actionable message rather than surfacing as a raw constraint
 * violation from the database (ADR-0053 §5).
 *
 * <p>
 * The database constraint remains the authority — this guard exists for the error message and for
 * the H2-backed test schema, not as a replacement for it.
 */
@Component
@RequiredArgsConstructor
public class ProductCodeUniquenessGuard {

    private final ProductRepository productRepository;

    /**
     * Rejects a product code already carried by a different product.
     *
     * @param codeType  code scheme being written; null means the product carries no typed code and
     *                  no constraint applies
     * @param code      code value being written; blank means the same
     * @param excludeId product being updated, so that a product keeping its own code is not a
     *                  conflict; null on create
     * @throws CatalogBusinessRuleException when another product already carries the code
     */
    public void assertUnique(ProductCodeType codeType, String code, UUID excludeId) {
        String normalized = ProductCodeNormalizer.normalize(code);
        if (codeType == null || normalized == null) {
            return;
        }
        boolean taken = excludeId == null
                ? productRepository.existsByProductCodeTypeAndProductCode(codeType, normalized)
                : productRepository.existsByProductCodeTypeAndProductCodeAndIdNot(codeType, normalized, excludeId);
        if (taken) {
            throw new CatalogBusinessRuleException(
                    "Product with productCodeType " + codeType + " and productCode " + normalized + " already exists");
        }
    }
}
