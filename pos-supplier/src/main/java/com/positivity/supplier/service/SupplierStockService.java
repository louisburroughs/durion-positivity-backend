package com.positivity.supplier.service;

import com.positivity.supplier.service.model.StockInquiryRequest;
import com.positivity.supplier.service.model.StockInquiryResponse;
import org.jspecify.annotations.NonNull;

/**
 * Live vendor stock inquiry — the platform's <strong>single approved synchronous cross-module
 * supplier read</strong> (ADR-0044 amendment dated 2026-08-10, ADR-0049 §4).
 *
 * <p><strong>Approved callers:</strong> <em>pos-catalog</em> (Product Detail composition) and
 * <em>pos-order</em> (procurement) only. The allowlisted edge is a named stock-inquiry client
 * class in each caller, not a module-to-module grant, and is enforced at class level in the
 * platform {@code DomainWallsTest}. No other module may call this interface synchronously.
 *
 * <p><strong>Degradation contract:</strong> this read never throws for vendor-side failure.
 * Vendor unavailability, an unbound capability, or invalid profile configuration surface as the
 * typed {@link StockInquiryResponse.Status} outcomes ({@code SUPPLIER_UNAVAILABLE},
 * {@code CAPABILITY_NOT_CONFIGURED} per ADR-0050 §3, {@code CONFIGURATION_ERROR} raised before
 * any network call per ADR-0050 §5) — callers must degrade gracefully and render without live
 * vendor stock rather than fail their own flow.
 *
 * <p>Implementation arrives with CAP-319 (Stock Inquiry, durion#374).
 */
public interface SupplierStockService {

    /**
     * Performs a real-time stock availability inquiry against the vendor bound to the given
     * profile.
     *
     * @param request the inquiry: vendor profile, delivery location, and article lines
     * @return a typed availability answer; never {@code null}, never a leaked vendor error
     */
    @NonNull
    StockInquiryResponse inquireLiveStock(@NonNull StockInquiryRequest request);
}
