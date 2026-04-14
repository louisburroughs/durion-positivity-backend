package com.positivity.catalog.service;

import com.positivity.catalog.internal.dto.ProductDetailView;
import java.util.UUID;

public interface ProductDetailService {

    /**
     * Retrieves a consolidated product detail view with pricing and availability.
     * Implements graceful degradation: returns partial data if non-critical
     * services are unavailable.
     *
     * @param productId  The unique product identifier
     * @param locationId The location/store identifier for location-specific data
     * @return ProductDetailView with status indicators for each data component
     */
    ProductDetailView getProductDetail(UUID productId, UUID locationId);
}
