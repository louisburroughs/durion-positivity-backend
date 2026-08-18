package com.positivity.order.internal.service;

import com.positivity.order.internal.client.InventoryPort;
import com.positivity.order.internal.client.InventoryResult;
import com.positivity.order.internal.entity.ExtInventoryAvailability;
import com.positivity.order.internal.entity.ExtProduct;
import com.positivity.order.internal.repository.ExtInventoryAvailabilityRepository;
import com.positivity.order.internal.repository.ExtProductRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Answers availability from {@code ext_inventory_availability} (CAP #1315), replacing the advisory
 * stub that reported every line sufficient because no replica feed existed yet.
 *
 * <h2>Absent means unknown, and unknown reports short</h2>
 *
 * A missing replica row is not evidence of stock. Reporting sufficient on absence would recreate
 * exactly the advisory behaviour this replaced, and would do it silently — a SKU that pos-inventory
 * has never emitted an availability fact for would sail through the gate. Reporting short instead
 * costs a backorder flag on a line that may well be stocked, which pos-inventory corrects when it
 * evaluates the reservation-request command against its own ledger. Being wrong toward "check this"
 * is recoverable; being wrong toward "promised" is not.
 *
 * <h2>Advisory, not authoritative</h2>
 *
 * The replica lags the ledger by normal Kafka latency, so this answer decides how a line is
 * labelled, never whether stock is committed. Commitment happens in pos-inventory.
 *
 * <h2>Two possible stock-item keys</h2>
 *
 * pos-inventory's ledger stores {@code stockItemId} as a plain string, and its own posting paths
 * disagree about what goes in it: the allocation/reservation path writes a product UUID rendered
 * as a string, while receiving paths write the human SKU. Availability facts inherit whichever the
 * ledger holds. Rather than guess, this looks the line up under the product UUID first and falls
 * back to the raw SKU — a line is only reported short when neither key has a row. Picking one key
 * would make the gate silently wrong for every item stocked through the other path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplicaInventoryPortAdapter implements InventoryPort {

    private final ExtInventoryAvailabilityRepository extInventoryAvailabilityRepository;
    private final ExtProductRepository extProductRepository;

    @Override
    public @NonNull InventoryResult checkAvailability(
            @NonNull String itemSku, int quantity, @Nullable UUID locationId) {
        if (locationId == null) {
            log.debug("No selling location for sku {}; reporting insufficient", itemSku);
            return new InventoryResult(false, 0);
        }
        Optional<ExtInventoryAvailability> replica = productIdFor(itemSku)
                .flatMap(productId -> extInventoryAvailabilityRepository.findByStockItemIdAndLocationId(
                        productId.toString(), locationId))
                .or(() -> extInventoryAvailabilityRepository.findByStockItemIdAndLocationId(itemSku, locationId));

        if (replica.isEmpty()) {
            log.debug(
                    "No availability replica row for sku {} at location {}; reporting insufficient",
                    itemSku,
                    locationId);
            return new InventoryResult(false, 0);
        }
        int atp = replica.get().getAvailableToPromiseQuantity();
        return new InventoryResult(atp >= quantity, Math.max(atp, 0));
    }

    private Optional<UUID> productIdFor(String itemSku) {
        return extProductRepository
                .findFirstBySkuIgnoreCaseAndActiveTrue(itemSku)
                .map(ExtProduct::getProductId);
    }
}
