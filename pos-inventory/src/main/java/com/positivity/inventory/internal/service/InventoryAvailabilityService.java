package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.dto.LocationAvailabilityDto;
import com.positivity.inventory.internal.enums.InventorySourceType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Service contract for reading inventory availability grouped by location.
 *
 * Issue: CAP-170 (#48)
 */
public interface InventoryAvailabilityService {

    /**
     * Returns availability for the requested product grouped by location.
     *
     * @param productId product identifier represented by stock item id in the
     *                  ledger
     * @return per-location availability list (empty when no ledger entries exist)
     */
    List<LocationAvailabilityDto> getAvailabilityByProduct(@NonNull UUID productId);

    /**
     * Variant of {@link #getAvailabilityByProduct(UUID)} with an optional forecast horizon
     * (odoo-parity A2, issue #1028): when {@code horizon} is non-null, the per-location
     * {@code incomingQty}/{@code outgoingQty}/{@code projectedAvailable} fields only count
     * supply and demand expected on or before it (documents without an expected date are
     * excluded from horizon-bounded results).
     *
     * @param productId product identifier represented by stock item id in the ledger
     * @param horizon   optional forecast date cutoff
     * @return per-location availability list (empty when no ledger entries exist)
     */
    List<LocationAvailabilityDto> getAvailabilityByProduct(@NonNull UUID productId, @Nullable Instant horizon);

    /**
     * Point-in-time on-hand per location (odoo-parity A3, issue #1029): computed by direct
     * ledger aggregation with {@code timestamp <= asOf} over on-hand-affecting event types —
     * the stock summary is not involved.
     *
     * <p>Returns on-hand only: allocation-derived ({@code availableToPromiseQuantity}) and
     * forecast fields are {@code null}, because historical allocation state is not reliably
     * reconstructable from ATP-neutral ledger events.
     *
     * <p>Requires {@code inventory:ledger:view} in addition to the endpoint's on-hand view
     * permission (history exposure); a future-dated {@code asOf} is rejected (422).
     *
     * @param productId product identifier represented by stock item id in the ledger
     * @param asOf      historical instant (inclusive)
     * @return per-location historical on-hand list (locations with no entries at or before
     *         {@code asOf} are absent)
     */
    List<LocationAvailabilityDto> getAvailabilityByProductAsOf(@NonNull UUID productId, @NonNull Instant asOf);

    /**
     * Returns availability for the requested product at a specific location.
     *
     * <p>
     * When {@code storageLocationId} is null, the result aggregates all
     * storage locations within the parent location.
     *
     * @param productSku        SKU identifier of the product
     * @param locationId        optional location identifier
     * @param storageLocationId optional sub-location identifier
     * @param sourceType        optional inventory source lookup type
     * @return availability view with on-hand, allocated, and ATP quantities
     * @throws com.positivity.inventory.internal.exception.ProductNotFoundException  if
     *                                                                               productSku
     *                                                                               not
     *                                                                               found
     * @throws com.positivity.inventory.internal.exception.LocationNotFoundException if
     *                                                                               locationId
     *                                                                               not
     *                                                                               found
     *                                                                               Issue:
     *                                                                               CAP-215
     *                                                                               Story
     *                                                                               #36
     */
    @NonNull
    AvailabilityView queryAvailability(
            @NonNull String productSku,
            @Nullable UUID locationId,
            @Nullable UUID storageLocationId,
            @Nullable InventorySourceType sourceType);

    /**
     * Variant of {@link #queryAvailability(String, UUID, UUID, InventorySourceType)} with an
     * optional forecast horizon (odoo-parity A2, issue #1028): when {@code horizon} is
     * non-null, the {@code incomingQty}/{@code outgoingQty}/{@code projectedAvailable} fields
     * only count supply and demand expected on or before it (documents without an expected
     * date are excluded from horizon-bounded results).
     *
     * @param productSku        SKU identifier of the product
     * @param locationId        optional location identifier
     * @param storageLocationId optional sub-location identifier
     * @param sourceType        optional inventory source lookup type
     * @param horizon           optional forecast date cutoff
     * @return availability view with on-hand, allocated, ATP, and forecast quantities
     */
    @NonNull
    AvailabilityView queryAvailability(
            @NonNull String productSku,
            @Nullable UUID locationId,
            @Nullable UUID storageLocationId,
            @Nullable InventorySourceType sourceType,
            @Nullable Instant horizon);
}
