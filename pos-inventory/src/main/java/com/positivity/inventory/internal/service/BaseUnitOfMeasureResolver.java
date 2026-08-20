package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.ExtProductUomReplica;
import com.positivity.inventory.internal.repository.ExtProductUomReplicaRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves a stock reference's base unit of measure for display, the shared read-side half of
 * ADR-0055's UOM-aware display work (#1416, "no human-facing quantity is rendered without one").
 *
 * <p>Every quantity this module stores that has no explicit UoM of its own is understood to be in
 * the product's base UoM (see {@code QuantityScaleGuard}, {@code UomConversionService}). This
 * resolves that implicit unit into an explicit one for DTOs to echo, so a bare number in a
 * response is never left to mean "whatever the reader assumes."
 */
@Component
@RequiredArgsConstructor
public class BaseUnitOfMeasureResolver {

    private static final String UOM_TYPE_BASE = "BASE";

    private final ExtProductUomReplicaRepository extProductUomReplicaRepository;

    /**
     * The product's declared base UoM code, or {@code null} when the stock reference does not
     * resolve to a catalog product id, or the product has no declared base row yet — the same
     * "undeclared means nothing to show" reading {@code QuantityScaleGuard} uses for divisibility.
     *
     * @param stockItemId a ledger stock-item id, SKU, or other stock reference that may or may not
     *     be a catalog product's UUID (see {@code QuantityScaleGuard#productIdOf})
     */
    public @Nullable String resolve(@Nullable String stockItemId) {
        return resolve(QuantityScaleGuard.productIdOf(stockItemId));
    }

    /** As {@link #resolve(String)}, when the catalog product id is already known. */
    public @Nullable String resolve(@Nullable UUID productId) {
        if (productId == null) {
            return null;
        }
        return extProductUomReplicaRepository.findByProductId(productId).stream()
                .filter(row -> UOM_TYPE_BASE.equals(row.getUomType()))
                .map(ExtProductUomReplica::getUomCode)
                .findFirst()
                .orElse(null);
    }

    /**
     * Batch form of {@link #resolve(UUID)} for a page of rows: one {@code IN} query instead of one
     * round trip per row. A product with no declared base UoM, or that is not among
     * {@code productIds}, is simply absent from the returned map rather than mapped to
     * {@code null} — callers already read a missing key as "nothing to show", same as the
     * single-id form.
     *
     * @param productIds catalog product ids to resolve; {@code null} entries are ignored
     */
    public @NonNull Map<UUID, String> resolveAll(@Nullable Collection<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> distinctIds = productIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> resolved = new HashMap<>();
        for (ExtProductUomReplica row : extProductUomReplicaRepository.findByProductIdIn(distinctIds)) {
            if (UOM_TYPE_BASE.equals(row.getUomType())) {
                resolved.putIfAbsent(row.getProductId(), row.getUomCode());
            }
        }
        return resolved;
    }
}
