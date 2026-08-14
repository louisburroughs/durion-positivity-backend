package com.positivity.catalog.service;

import com.positivity.catalog.internal.dto.ProductCodeDuplicate;
import com.positivity.catalog.internal.dto.ProductCodeKind;
import com.positivity.catalog.internal.dto.ProductCodeMatch;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * Deterministic, exact-match product lookup by EAN/UPC product code (ADR-0053 §5).
 *
 * <p>
 * This is the matching foundation the supplier PRICAT consumer resolves lines against: EAN first,
 * then UPC via the line's cross-reference code. Matching is exact and case-sensitive after
 * trimming — no fuzzy matching, no normalisation of check digits, and no auto-creation of
 * products. A line that resolves to nothing here is quarantined by the caller, never guessed at.
 *
 * <p>
 * A lookup can return at most one product because {@code (productCodeType, productCode)} is unique
 * for the constrained code kinds.
 *
 * Issue: CAP-318 #1232
 */
public interface ProductCodeLookupService {

    /**
     * Resolves the single product carrying the given code under the given scheme.
     *
     * @param codeType code scheme to match within; EAN and UPC are matched independently, so the
     *                 same digits under a different scheme are a different code
     * @param code     exact code value; surrounding whitespace is trimmed, and a blank value never
     *                 matches
     * @return the matched product, or empty when no product carries the code
     */
    @NonNull
    Optional<ProductCodeMatch> findByProductCode(@NonNull ProductCodeKind codeType, @NonNull String code);

    /**
     * Reports product-code values carried by more than one product.
     *
     * <p>
     * On a schema where the uniqueness constraint is applied this is always empty; it stays on the
     * contract because the pre-constraint migration fails safe on dirty data and operators need a
     * supported way to enumerate what must be cleaned up.
     *
     * @return one entry per duplicated {@code (codeType, code)} pair, empty when the data is clean
     */
    @NonNull
    List<ProductCodeDuplicate> findDuplicateProductCodes();
}
