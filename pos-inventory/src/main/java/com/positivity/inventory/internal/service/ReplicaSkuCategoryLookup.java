package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a SKU's catalog classification out of the {@code ext_product} replica (#1514).
 *
 * <p>Unconditionally registered, unlike {@link ReplicaSkuCategoryProvider}: this answers a question
 * nothing asked before #1514, so switching it off could only break the new putaway matcher without
 * protecting anything.
 *
 * <p>A stock item id that is not a product UUID resolves to empty without a query. The replica is
 * keyed by product id, so a non-UUID id is a SKU this replica structurally cannot know — not a
 * lookup miss worth a round trip.
 */
@Component
public class ReplicaSkuCategoryLookup implements SkuCategoryLookup {

    private final ExtProductReplicaRepository extProductReplicaRepository;

    public ReplicaSkuCategoryLookup(ExtProductReplicaRepository extProductReplicaRepository) {
        this.extProductReplicaRepository = extProductReplicaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<SkuCategoryRef> categoryRefOf(@NonNull String stockItemId) {
        UUID productId = productIdOf(stockItemId);
        if (productId == null) {
            return Optional.empty();
        }
        return extProductReplicaRepository
                .findById(productId)
                .map(ReplicaSkuCategoryLookup::refOf)
                .filter(ref -> !ref.isEmpty());
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Map<String, SkuCategoryRef> categoryRefOfAll(@NonNull Collection<String> stockItemIds) {
        Map<UUID, String> byProductId = productIdsOf(stockItemIds);
        if (byProductId.isEmpty()) {
            return Map.of();
        }
        Map<String, SkuCategoryRef> refs = new HashMap<>(byProductId.size());
        for (ExtProductReplica replica : extProductReplicaRepository.findAllById(byProductId.keySet())) {
            SkuCategoryRef ref = refOf(replica);
            String stockItemId = byProductId.get(replica.getProductId());
            if (!ref.isEmpty() && stockItemId != null) {
                refs.put(stockItemId, ref);
            }
        }
        return Map.copyOf(refs);
    }

    /**
     * Product ids of the resolvable stock item ids, mapped back to the caller's original string so
     * the answer is keyed the way the caller asked. Unparseable ids are dropped, not guessed at.
     */
    private static Map<UUID, String> productIdsOf(Collection<String> stockItemIds) {
        if (stockItemIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> byProductId = new HashMap<>(stockItemIds.size());
        for (String stockItemId : stockItemIds) {
            UUID productId = productIdOf(stockItemId);
            if (productId != null) {
                byProductId.put(productId, stockItemId);
            }
        }
        return byProductId;
    }

    private static UUID productIdOf(String stockItemId) {
        try {
            return UUID.fromString(stockItemId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static SkuCategoryRef refOf(ExtProductReplica replica) {
        return new SkuCategoryRef(
                replica.getCategoryId(),
                trimToNull(replica.getCategoryName()),
                replica.getSubcategoryId(),
                trimToNull(replica.getSubcategoryName()));
    }

    /** Blank and absent are the same statement from catalog: the product carries no category. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
