package com.positivity.catalog.service;

import com.positivity.catalog.internal.dto.ProductLifecycleResponse;
import com.positivity.catalog.internal.dto.ProductLifecycleUpdateRequest;
import com.positivity.catalog.internal.dto.ProductReplacementRequest;
import com.positivity.catalog.internal.entity.ProductLifecycleState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ProductLifecycleService {

    ProductLifecycleResponse getLifecycle(UUID productId);

    ProductLifecycleResponse updateLifecycle(UUID productId, ProductLifecycleUpdateRequest request);

    ProductLifecycleResponse.ReplacementOption addReplacement(UUID productId, ProductReplacementRequest request);

    ProductLifecycleResponse setLifecycleState(
            UUID productId,
            ProductLifecycleState newState,
            Instant effectiveAt,
            String overrideReason,
            LocalDate effectiveDate);

    ProductLifecycleResponse.ReplacementOption addReplacementProduct(
            UUID discontinuedProductId,
            UUID replacementProductId,
            int priorityOrder,
            String notes,
            Instant effectiveAt);

    List<ProductLifecycleResponse.ReplacementOption> getReplacementProducts(UUID productId);
}
