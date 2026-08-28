package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.supplierhint.SupplierStockHintView;
import com.positivity.inventory.internal.enums.SupplierHintIdentityKind;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Reads supplier availability hints — what vendors last said about their own stock (CAP-322,
 * #1312).
 *
 * <p>Everything this returns is advisory. Supplier availability is <strong>not</strong> owned
 * stock: it never enters valuation (ADR-0048), it is never part of on-hand availability-to-promise,
 * and it never satisfies a gate on committing stock. It informs what we can tell a customer about a
 * future need, which is a different question from what we can promise off the shelf today.
 *
 * <p>Results are per vendor and are never aggregated across vendors: summing two vendors' figures
 * would invent a number no vendor stated. An empty result means no vendor has reported the article
 * — it does not mean the article is out of stock anywhere.
 */
public interface SupplierStockHintService {

    /**
     * Every vendor's current hint for a catalog product.
     *
     * <p>Only hints whose article has been resolved to this product are returned. Unresolved hints
     * are retained and remain visible through {@link #findByCode}, since an article we could not
     * resolve is a gap in our catalog data rather than a reason to discard what the vendor said.
     *
     * @param productId catalog product to report on
     * @return one entry per vendor reporting the product, newest fetch first; empty when none do
     */
    @NonNull
    List<SupplierStockHintView> findByProduct(@NonNull UUID productId);

    /**
     * Every vendor's current hint for an article identifier, resolved or not.
     *
     * <p>The lookup matches the identifier the caller holds against that identifier wherever a
     * vendor stated it, not only where it was elected as the hint's key — a hint keyed on our
     * article code is still findable by the EAN the same vendor stated on the same line.
     *
     * @param identityKind which identifier scheme {@code value} belongs to
     * @param value        exact identifier value; trimmed, otherwise matched verbatim
     * @return one entry per vendor stating that identifier, newest fetch first
     */
    @NonNull
    List<SupplierStockHintView> findByCode(@NonNull SupplierHintIdentityKind identityKind, @NonNull String value);
}
