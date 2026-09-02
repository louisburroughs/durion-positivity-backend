package com.positivity.supplier.internal.stockinquiry.service;

import com.positivity.supplier.internal.stockinquiry.service.model.StockAvailabilityView;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Product-keyed live availability across every enabled STOCK_INQUIRY vendor (#1637 decision 1).
 *
 * <p>The caller names a catalog product — by id or by SKU, never by EAN or vendor article code —
 * and this service resolves the vendor-queryable identity from the local catalog replica, asks
 * every enabled stock-inquiry vendor concurrently, and assembles whatever answered within the
 * configured deadline. Vendor-side failure is a per-vendor status, never an exception.
 */
public interface SupplierStockAvailabilityService {

    /**
     * Checks one product's live availability with every enabled stock-inquiry vendor.
     *
     * @param productId catalog product id; exactly one of this and {@code sku} must be given
     * @param sku catalog SKU; exactly one of this and {@code productId} must be given
     * @param deliveryLocationId the receiving location the question is about — availability is
     *     consignee-specific
     * @param quantity the quantity whose availability is checked; {@code >= 1}
     * @return per-vendor results, partial by design; never null
     * @throws com.positivity.supplier.internal.exception.SupplierValidationException when neither
     *     or both product identities are given
     * @throws com.positivity.supplier.internal.exception.SupplierNotFoundException when the
     *     identity resolves to no vendor-queryable code in the catalog replica
     * @throws com.positivity.supplier.internal.exception.SupplierConflictException when the SKU
     *     ambiguously names more than one replicated product
     */
    @NonNull
    StockAvailabilityView checkAvailability(
            @Nullable UUID productId, @Nullable String sku, @NonNull UUID deliveryLocationId, int quantity);
}
